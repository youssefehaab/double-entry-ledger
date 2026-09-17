package com.ledger.service.api.dto;

import com.ledger.service.domain.Account;
import com.ledger.service.domain.AccountType;
import java.time.Instant;
import java.util.UUID;

/**
 * Response body for account endpoints. Note there is no {@code balance}
 * field: balances are derived from entries (starting Phase 2), never
 * stored on the account, so there is nothing to serialize here yet.
 */
public record AccountResponse(
        UUID id,
        String name,
        String currency,
        AccountType accountType,
        Instant createdAt
) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getCurrency(),
                account.getAccountType(),
                account.getCreatedAt()
        );
    }
}
