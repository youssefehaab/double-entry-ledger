package com.ledger.service.api.dto;

import com.ledger.service.domain.EntryDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One requested debit/credit posting within {@link CreateTransactionRequest}.
 *
 * <p>{@code amount} is validated {@code > 0} here at the app level
 * ({@link Positive}) purely to return a clean 400 with a field-level
 * message instead of letting an invalid value reach the DB and bounce off
 * the {@code chk_entries_amount_positive} CHECK constraint as a raw,
 * unfriendly SQL error. The DB constraint remains the ultimate backstop.
 */
@Schema(description = "A single debit or credit posting against an account")
public record EntryRequest(

        @NotNull(message = "accountId must not be null")
        @Schema(description = "Account this entry posts against", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID accountId,

        @NotNull(message = "amount must not be null")
        @Positive(message = "amount must be positive")
        @Schema(description = "Unsigned amount; sign is carried by direction, not the amount itself",
                example = "100.00", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal amount,

        @NotNull(message = "direction must be one of DEBIT, CREDIT")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        EntryDirection direction
) {
}
