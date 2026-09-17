package com.ledger.service.service;

import com.ledger.service.api.dto.AccountResponse;
import com.ledger.service.api.dto.BalanceResponse;
import com.ledger.service.api.dto.CreateAccountRequest;
import com.ledger.service.api.dto.PagedEntriesResponse;
import com.ledger.service.domain.Account;
import com.ledger.service.domain.Entry;
import com.ledger.service.repository.AccountRepository;
import com.ledger.service.repository.EntryRepository;
import com.ledger.service.service.exception.AccountNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final EntryRepository entryRepository;

    public AccountService(AccountRepository accountRepository, EntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
    }

    /**
     * Creates and persists a new account.
     *
     * <p>Note: creating an account is not an idempotent operation in this
     * phase - there is no client-supplied idempotency key on
     * {@link CreateAccountRequest}, unlike transaction creation, which is
     * keyed by {@code idempotency_key} (Phase 2). A retried POST /accounts
     * request will create a second, distinct account. This is a deliberate
     * scope decision for Phase 1: accounts are reference data set up
     * up-front, not a high-frequency retried operation the way transaction
     * posting is.
     */
    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        Account account = new Account(
                request.name().trim(),
                normalizeCurrency(request.currency()),
                request.accountType()
        );
        // saveAndFlush (not save): forces the INSERT immediately so the
        // DB/Hibernate-generated createdAt (@CreationTimestamp) is populated
        // on the entity before we map it to the response, instead of
        // staying null until the surrounding transaction eventually flushes.
        Account saved = accountRepository.saveAndFlush(account);
        return AccountResponse.from(saved);
    }

    private String normalizeCurrency(String currency) {
        return currency.trim().toUpperCase();
    }

    /**
     * Computes an account's balance on the fly by summing its entries -
     * never from a stored column (there is none). See {@link
     * com.ledger.service.repository.EntryRepository#sumSignedAmountsByAccountId}
     * for the sign convention (debit-positive).
     *
     * @throws AccountNotFoundException if no account has this id (mapped to 404)
     */
    @Transactional(readOnly = true)
    public BalanceResponse getBalance(UUID accountId) {
        requireAccountExists(accountId);
        BigDecimal balance = entryRepository.sumSignedAmountsByAccountId(accountId);
        return new BalanceResponse(accountId, balance, Instant.now());
    }

    /**
     * Paginated entries for an account, ordered by created_at then id for
     * deterministic, stable pagination.
     *
     * @throws AccountNotFoundException if no account has this id (mapped to 404)
     */
    @Transactional(readOnly = true)
    public PagedEntriesResponse getEntries(UUID accountId, Pageable pageable) {
        requireAccountExists(accountId);
        // Only page number/size from the caller-supplied Pageable are
        // honored; sort order is fixed (created_at, id) rather than
        // client-controlled, so pagination stays deterministic regardless
        // of what sort params a client passes.
        Pageable fixedOrderPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Page<Entry> page = entryRepository.findByAccountIdOrderByCreatedAtAscIdAsc(accountId, fixedOrderPageable);
        return PagedEntriesResponse.from(page);
    }

    private void requireAccountExists(UUID accountId) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
    }
}
