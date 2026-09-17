package com.ledger.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response body for {@code GET /accounts/{id}/balance}.
 *
 * <p>Always computed on the fly from {@code entries} (there is no stored
 * balance column - see the Account entity). Sign convention: DEBIT entries
 * add, CREDIT entries subtract ("debit-positive"); see
 * {@link com.ledger.service.repository.EntryRepository#sumSignedAmountsByAccountId}
 * for the full rationale.
 */
@Schema(description = "An account's balance, computed on the fly by summing its entries "
        + "(debit-positive: DEBIT adds, CREDIT subtracts)")
public record BalanceResponse(
        UUID accountId,
        BigDecimal balance,
        Instant asOf
) {
}
