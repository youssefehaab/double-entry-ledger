package com.ledger.service.service;

import com.ledger.service.api.dto.CreateTransactionRequest;
import com.ledger.service.api.dto.EntryRequest;
import com.ledger.service.api.dto.TransactionResponse;
import com.ledger.service.domain.Account;
import com.ledger.service.domain.Entry;
import com.ledger.service.domain.Transaction;
import com.ledger.service.repository.AccountRepository;
import com.ledger.service.repository.EntryRepository;
import com.ledger.service.repository.TransactionRepository;
import com.ledger.service.service.exception.AccountNotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns every DB read/write for transactions and their entries.
 *
 * <p>Deliberately a separate Spring bean from {@link TransactionService}
 * (not just separate methods on the same class): {@code TransactionService}
 * needs to catch a {@link org.springframework.dao.DataIntegrityViolationException}
 * thrown by {@link #createAndPersist} and then, in a <em>separate</em> DB
 * transaction, re-read the row that won the race via {@link
 * #findByIdempotencyKey}. That only works correctly if the two calls go
 * through Spring's transactional proxy as two independent invocations - a
 * self-invocation of an {@code @Transactional} method from within the same
 * class silently skips the proxy (a well-known Spring AOP limitation) and
 * would run inside whatever transaction (or lack of one) the caller was
 * already in, which is exactly wrong here: after a constraint violation,
 * the failed transaction must actually be rolled back before the recovery
 * read runs. Splitting into two beans sidesteps the self-invocation trap
 * entirely instead of relying on more fragile fixes (e.g. self-injection).
 */
@Service
public class TransactionWriter {

    private final TransactionRepository transactionRepository;
    private final EntryRepository entryRepository;
    private final AccountRepository accountRepository;

    public TransactionWriter(
            TransactionRepository transactionRepository,
            EntryRepository entryRepository,
            AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public Optional<TransactionResponse> findByIdempotencyKey(String idempotencyKey) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Optional<TransactionResponse> findById(UUID transactionId) {
        return transactionRepository.findById(transactionId)
                .map(this::toResponse);
    }

    /**
     * Validates that every referenced account exists, then creates the
     * Transaction (status POSTED, postedAt set) and its Entries atomically
     * in one DB transaction.
     *
     * <p>Isolation: default READ COMMITTED is sufficient here. The
     * invariant this method must never violate - "no two POSTED
     * transactions share an idempotency_key" - is guaranteed unconditionally
     * by the {@code uq_transactions_idempotency_key} UNIQUE constraint
     * (checked immediately at INSERT, not deferred), regardless of
     * isolation level. Two concurrent callers racing to insert the same
     * brand-new key will have exactly one INSERT succeed and the other fail
     * with a {@link org.springframework.dao.DataIntegrityViolationException}
     * - see {@link TransactionService#createTransaction} for how the loser
     * recovers. A stronger isolation level would not remove the need for
     * that recovery path (the unique constraint still races), so none is
     * used here; an advisory lock keyed on the idempotency key to avoid the
     * loser's wasted insert work is a reasonable Phase 3 concurrency
     * optimization, not required for correctness now.
     *
     * @throws AccountNotFoundException if any entries[].accountId does not exist
     * @throws org.springframework.dao.DataIntegrityViolationException if the
     *         idempotency key was concurrently claimed by another request,
     *         or another DB-level constraint is violated
     */
    @Transactional
    public TransactionResponse createAndPersist(CreateTransactionRequest request, String idempotencyKey) {
        Map<UUID, Account> accountsById = loadAccountsOrThrow(request.entries());

        Transaction transaction = new Transaction(idempotencyKey, request.description().trim());
        transaction.markPosted(Instant.now());
        // Flushed immediately (not just saved) so the immediate UNIQUE
        // constraint on idempotency_key is checked - and can throw - right
        // here, before any entry rows are ever inserted for this attempt.
        transactionRepository.saveAndFlush(transaction);

        List<Entry> entries = new ArrayList<>();
        for (EntryRequest entryRequest : request.entries()) {
            Account account = accountsById.get(entryRequest.accountId());
            entries.add(new Entry(transaction, account, entryRequest.amount(), entryRequest.direction()));
        }
        entryRepository.saveAll(entries);
        // Flush now (rather than waiting for implicit flush-on-commit) so
        // that, within this same DB transaction, the deferred balance
        // constraint trigger (V5 migration) is queued against the actual
        // inserted rows and will be checked at commit exactly as designed.
        entryRepository.flush();

        return TransactionResponse.from(transaction, entries);
    }

    private Map<UUID, Account> loadAccountsOrThrow(List<EntryRequest> entryRequests) {
        // LinkedHashMap only to keep iteration order stable/predictable for
        // tests and logs; not otherwise load-bearing.
        Map<UUID, Account> accountsById = new LinkedHashMap<>();
        List<UUID> missing = new ArrayList<>();

        for (EntryRequest entryRequest : entryRequests) {
            UUID accountId = entryRequest.accountId();
            if (accountsById.containsKey(accountId) || missing.contains(accountId)) {
                continue;
            }
            accountRepository.findById(accountId).ifPresentOrElse(
                    account -> accountsById.put(accountId, account),
                    () -> missing.add(accountId));
        }

        if (!missing.isEmpty()) {
            throw new AccountNotFoundException(missing);
        }
        return accountsById;
    }

    private TransactionResponse toResponse(Transaction transaction) {
        return TransactionResponse.from(transaction, entryRepository.findByTransactionId(transaction.getId()));
    }
}
