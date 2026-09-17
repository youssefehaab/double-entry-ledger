package com.ledger.service.api.dto;

import com.ledger.service.domain.EntryDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
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
 *
 * <p>{@code amount} is also bounded to {@link Digits}(integer = 15,
 * fraction = 4), matching the {@code entries.amount NUMERIC(19, 4)} column
 * exactly (19 total digits = 15 integer + 4 fraction). Without this check,
 * an implausibly large or over-precise amount reaches the DB and is
 * rejected by the column's numeric overflow, which JDBC/Hibernate surfaces
 * as a {@link org.springframework.dao.DataIntegrityViolationException} -
 * the same exception type used for the idempotency-key UNIQUE-violation
 * race (see {@code TransactionService}), and mapped by {@code
 * GlobalExceptionHandler}'s generic fallback to 409. That would wrongly
 * overload the documented meaning of 409 ("safe to refetch the original
 * idempotent result") for a request that was never accepted in the first
 * place. Catching it here instead - before any DB call - gives a precise
 * 400 with a message naming the actual problem, and keeps 409 meaning only
 * what it is documented to mean.
 */
@Schema(description = "A single debit or credit posting against an account")
public record EntryRequest(

        @NotNull(message = "accountId must not be null")
        @Schema(description = "Account this entry posts against", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID accountId,

        @NotNull(message = "amount must not be null")
        @Positive(message = "amount must be positive")
        @Digits(integer = 15, fraction = 4,
                message = "amount exceeds maximum precision: at most 15 integer digits and 4 fraction digits are allowed")
        @Schema(description = "Unsigned amount; sign is carried by direction, not the amount itself. "
                + "At most 15 integer digits and 4 fraction digits.",
                example = "100.00", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal amount,

        @NotNull(message = "direction must be one of DEBIT, CREDIT")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        EntryDirection direction
) {
}
