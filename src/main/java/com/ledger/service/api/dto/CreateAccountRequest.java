package com.ledger.service.api.dto;

import com.ledger.service.domain.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /accounts}.
 *
 * <p>Kept separate from {@link com.ledger.service.domain.Account} so the
 * JPA entity is never exposed directly in the API layer - the wire contract
 * and the persistence model are allowed to evolve independently. Follow
 * this pattern for every future endpoint too.
 */
public record CreateAccountRequest(

        @NotBlank(message = "name must not be blank")
        String name,

        @NotBlank(message = "currency must not be blank")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter uppercase ISO 4217 code")
        String currency,

        @NotNull(message = "accountType must be one of ASSET, LIABILITY, EQUITY")
        AccountType accountType
) {
}
