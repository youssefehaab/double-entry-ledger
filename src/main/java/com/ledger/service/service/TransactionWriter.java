package com.ledger.service.service;

import com.ledger.service.api.dto.CreateTransactionRequest;
import com.ledger.service.api.dto.EntryRequest;
import com.ledger.service.api.dto.TransactionResponse;
import com.ledger.service.domain.Account;
import com.ledger.service.domain.Entry;
import com.ledger.service.domain.OutboxEvent;
import com.ledger.service.domain.Transaction;
import com.ledger.service.repository.AccountRepository;
import com.ledger.service.repository.EntryRepository;
import com.ledger.service.repository.OutboxEventRepository;
import com.ledger.service.repository.TransactionRepository;
import com.ledger.service.service.exception.AccountNotFoundException;
import com.ledger.service.service.fx.FxConversionResult;
import com.ledger.service.service.fx.FxRateProvider;
import com.ledger.service.service.outbox.OutboxEventFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventFactory outboxEventFactory;
    private final FxRateProvider fxRateProvider;
    private final String baseCurrency;

    public TransactionWriter(
            TransactionRepository transactionRepository,
            EntryRepository entryRepository,
            AccountRepository accountRepository,
            OutboxEventRepository outboxEventRepository,
            OutboxEventFactory outboxEventFactory,
            FxRateProvider fxRateProvider,
            @Value("${ledger.fx.base-currency:USD}") String baseCurrency) {
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.accountRepository = accountRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventFactory = outboxEventFactory;
        this.fxRateProvider = fxRateProvider;
        // Normalized once, here, the same way AccountService.normalizeCurrency
        // normalizes account currencies (trim + uppercase) - so the two are
        // guaranteed comparable. Without this, an operator setting
        // FX_BASE_CURRENCY=usd (lowercase) would make DbFxRateProvider's
        // same-currency identity short-circuit ("USD".equals("usd") == false)
        // fail to match, incorrectly falling through to a DB lookup and
        // likely throwing UnsupportedCurrencyPairException for what should
        // be a same-currency no-op conversion.
        this.baseCurrency = baseCurrency.trim().toUpperCase();
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
     * Locks every referenced account (see {@link #lockAccountsInOrder}),
     * validates that all of them exist, then creates the Transaction
     * (status POSTED, postedAt set) and its Entries atomically in one DB
     * transaction.
     *
     * <p>FX conversion (Phase 1 of v1 -&gt; v1.1): for every entry, {@link
     * #fxRateProvider} converts {@code entryRequest.amount()} from that
     * entry's account's currency into {@link #baseCurrency}, and the result
     * (converted amount, rate used, rate's effective-at) is passed straight
     * into the {@link Entry} constructor - computed and persisted inside
     * this same {@code @Transactional} method, atomically with the entry
     * row itself, never as a separate step that could drift from what was
     * actually posted or be computed post-hoc. A same-currency entry goes
     * through this identical call ({@code fxRateProvider.convert(cur, cur,
     * amount)}); see {@code DbFxRateProvider}'s javadoc for why that is
     * still guaranteed to be a pure identity conversion. If no FX rate is
     * available for a genuinely cross-currency pair, {@link
     * com.ledger.service.service.exception.UnsupportedCurrencyPairException}
     * propagates from here before any row is written, rolling back this
     * whole transaction (including the already-flushed Transaction row).
     *
     * <p>App-level balance pre-check ({@link TransactionBalanceValidator})
     * also now happens here, on the FX-converted entries, rather than in
     * {@link TransactionService} before this method is even called as it
     * did pre-FX: base_currency_amount does not exist until accounts are
     * loaded and converted above, so this is the earliest point a
     * currency-aware balance check is possible. Still strictly before any
     * {@link Entry} row is inserted - see that class's javadoc for the full
     * reasoning on why a currency-blind pre-check on raw request amounts is
     * no longer correct once entries can span multiple currencies.
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
     * loser's wasted insert work is a reasonable further concurrency
     * optimization, not required for correctness now.
     *
     * <p>Phase 2 of v1 -&gt; v1.1 (outbox pattern): exactly one {@link
     * OutboxEvent} row ({@code event_type = TransactionPosted}) is inserted
     * here too, in this SAME {@code @Transactional} method - after entries
     * are saved/flushed and validated as balanced, but still strictly
     * before this method returns and its transaction commits. This is the
     * entire point of the outbox pattern: "the transaction posted" and "an
     * event describing it was durably recorded" are the same atomic DB
     * commit, never two independent writes that could drift (e.g. the
     * entries commit but the app crashes before a separate, later Kafka
     * publish call - or a Kafka publish succeeds but the enclosing DB
     * transaction then rolls back). Proof this is the same transaction: no
     * {@code @Transactional} boundary appears between {@code
     * entryRepository.flush()} above and {@code
     * outboxEventRepository.save(outboxEvent)} below - both run under the
     * single {@code @Transactional} on this method, and if {@link
     * TransactionBalanceValidator#validateBalanced} or the entries flush
     * throws, this method returns via exception before the outbox insert is
     * ever reached, so nothing is saved at all (see
     * {@code TransactionWriterOutboxAtomicityIntegrationTest} for a test
     * that forces exactly this rollback and asserts zero outbox rows
     * exist). Nothing else in this codebase ever writes to Kafka directly -
     * {@code OutboxRelay} is the only reader of PENDING rows here, running
     * in its own separate, later transaction(s).
     *
     * @throws AccountNotFoundException if any entries[].accountId does not exist
     * @throws org.springframework.dao.DataIntegrityViolationException if the
     *         idempotency key was concurrently claimed by another request,
     *         or another DB-level constraint is violated
     */
    @Transactional
    public TransactionResponse createAndPersist(CreateTransactionRequest request, String idempotencyKey) {
        Map<UUID, Account> accountsById = lockAccountsInOrder(request.entries());

        Transaction transaction = new Transaction(idempotencyKey, request.description().trim());
        transaction.markPosted(Instant.now());
        // Flushed immediately (not just saved) so the immediate UNIQUE
        // constraint on idempotency_key is checked - and can throw - right
        // here, before any entry rows are ever inserted for this attempt.
        transactionRepository.saveAndFlush(transaction);

        List<Entry> entries = new ArrayList<>();
        for (EntryRequest entryRequest : request.entries()) {
            Account account = accountsById.get(entryRequest.accountId());
            FxConversionResult conversion = fxRateProvider.convert(
                    account.getCurrency(), baseCurrency, entryRequest.amount());
            entries.add(new Entry(
                    transaction,
                    account,
                    entryRequest.amount(),
                    entryRequest.direction(),
                    conversion.convertedAmount(),
                    conversion.rateUsed(),
                    conversion.rateEffectiveAt()));
        }
        TransactionBalanceValidator.validateBalanced(entries);
        entryRepository.saveAll(entries);
        // Flush now (rather than waiting for implicit flush-on-commit) so
        // that, within this same DB transaction, the deferred balance
        // constraint trigger (V5 migration) is queued against the actual
        // inserted rows and will be checked at commit exactly as designed.
        entryRepository.flush();

        // Same DB transaction as the entries flush above - see this
        // method's javadoc for why that atomicity is the entire point.
        OutboxEvent outboxEvent = outboxEventFactory.transactionPosted(transaction, entries);
        outboxEventRepository.save(outboxEvent);

        return TransactionResponse.from(transaction, entries);
    }

    /**
     * Locks every distinct account referenced by {@code entryRequests} with
     * {@code SELECT ... FOR UPDATE} ({@link AccountRepository#findByIdForUpdate}),
     * in ascending account-id order, <b>before</b> the Transaction or any
     * Entry row is written - this is the concurrency-control mechanism for
     * Phase 3 (pessimistic locking, chosen over {@code @Version}-based
     * optimistic locking; see the class-level rationale below).
     *
     * <h2>Why lock accounts at all, given balances are derived, not stored</h2>
     * {@link Account} deliberately has no stored balance column, so there is
     * no classic read-modify-write "lost update" for this lock to prevent:
     * posting is a pure INSERT of new, independent {@link Entry} rows, two
     * concurrent posts to the same account never overwrite each other's
     * data, and the V5 deferred constraint trigger already guarantees each
     * transaction's own entries are balanced atomically at COMMIT no matter
     * what else is happening concurrently. Being honest about what this
     * lock does and does not buy, rather than overselling it:
     * <ul>
     *   <li><b>Not protected (because it needs no protecting):</b>
     *       {@code GET /accounts/{id}/balance} correctness. Postgres
     *       MVCC/snapshot isolation already guarantees a concurrent balance
     *       read sees either all of a committed transaction's entries or
     *       none of them - a transaction's entries commit together as one
     *       atomic unit, so a reader can never observe a torn, half-posted
     *       transaction. That guarantee falls out of "one DB transaction
     *       per post" alone and needs no row lock.</li>
     *   <li><b>Actually protected:</b> a future check-then-act rule keyed on
     *       an account's current derived balance - the canonical example is
     *       an overdraft/sufficient-funds check ("read balance, then decide
     *       whether to allow this post"). No such rule exists in this
     *       phase's scope, but it is exactly the kind of rule a ledger
     *       service adds next, and it is exactly where a TOCTOU race would
     *       otherwise live: without this lock, two concurrent transactions
     *       against the same account could both read the same pre-post
     *       balance, both pass the check, and both post, overdrawing the
     *       account despite the check. Taking the row lock before any
     *       entries are inserted means a second transaction touching the
     *       same account cannot even begin reading account state for such a
     *       decision until the first transaction has committed or rolled
     *       back all of its entries, closing that window by construction.</li>
     *   <li><b>Secondary benefit:</b> serializes writers touching the same
     *       account, giving predictable one-at-a-time posting order on that
     *       account rather than leaving arbitrary interleaving to be sorted
     *       out by the deferred trigger and MVCC at commit time.</li>
     * </ul>
     *
     * <h2>Lock ordering / deadlock avoidance</h2>
     * Locks are acquired in ascending account-id order - never in the order
     * accounts appear in {@code entryRequests} (i.e. never in payload/
     * debit-first order). Two different transactions can reference the same
     * two accounts with opposite roles (T1: debit A / credit B; T2: debit B
     * / credit A). Locking in payload order would have T1 acquire A then B
     * while T2 concurrently acquires B then A - each can end up holding one
     * lock while waiting on the other, the classic circular-wait deadlock
     * shape. Sorting collapses both transactions' acquisition order onto
     * the same global order (A then B, always, regardless of which is the
     * debit and which is the credit leg), which makes that interleaving
     * impossible: whichever transaction reaches A first also reaches B
     * first, so the second transaction simply waits for the first to
     * finish - it can never hold a lock the first transaction also needs
     * while waiting on a lock the first transaction holds.
     */
    private Map<UUID, Account> lockAccountsInOrder(List<EntryRequest> entryRequests) {
        List<UUID> distinctSortedIds = entryRequests.stream()
                .map(EntryRequest::accountId)
                .distinct()
                .sorted()
                .toList();

        // LinkedHashMap only to keep iteration order stable/predictable for
        // tests and logs; not otherwise load-bearing.
        Map<UUID, Account> accountsById = new LinkedHashMap<>();
        List<UUID> missing = new ArrayList<>();

        for (UUID accountId : distinctSortedIds) {
            accountRepository.findByIdForUpdate(accountId).ifPresentOrElse(
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
