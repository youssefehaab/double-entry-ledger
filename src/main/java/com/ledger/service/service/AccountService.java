package com.ledger.service.service;

import com.ledger.service.api.dto.AccountResponse;
import com.ledger.service.api.dto.CreateAccountRequest;
import com.ledger.service.domain.Account;
import com.ledger.service.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
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
}
