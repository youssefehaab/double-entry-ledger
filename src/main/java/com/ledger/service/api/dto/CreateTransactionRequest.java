package com.ledger.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Request body for {@code POST /transactions}.
 *
 * <p>Kept separate from the {@code Transaction}/{@code Entry} JPA entities,
 * same pattern as Phase 1's {@link CreateAccountRequest}.
 *
 * <p>{@code entries} only requires {@link NotEmpty} (not a minimum size of
 * 2) at the bean-validation layer: a list of fewer than two entries can
 * never balance (every entry has a strictly positive amount - see
 * {@link EntryRequest}), so it is already rejected by the balance check
 * with a clear 422, and that is the one, consistent path for "entries don't
 * balance" rather than splitting the same underlying business rule across
 * two different validation mechanisms with two different status codes.
 */
@Schema(description = "Request to create and post a balanced double-entry transaction")
public record CreateTransactionRequest(

        @NotBlank(message = "description must not be blank")
        @Schema(example = "Invoice #1042 payment", requiredMode = Schema.RequiredMode.REQUIRED)
        String description,

        @NotEmpty(message = "entries must not be empty")
        @Valid
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<EntryRequest> entries
) {
}
