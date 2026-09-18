package com.ledger.service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the top-level {@code info} block for the springdoc-generated
 * OpenAPI document ({@code GET /v3/api-docs}), which is exported to
 * {@code openapi.yaml} at the repo root and is the contract other
 * tooling/agents read - so it needs a real title/description/version, not
 * springdoc's bare default.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Double-Entry Ledger API")
                        .description("A double-entry ledger service. Every transaction is a balanced "
                                + "set of DEBIT/CREDIT entries (sum(DEBIT) == sum(CREDIT)), enforced "
                                + "both at the application level and, as the ultimate backstop, by a "
                                + "PostgreSQL deferred constraint trigger. Entries are append-only; "
                                + "account balances are always derived from entries, never stored.")
                        .version("0.3.0")
                        .contact(new Contact().name("Ledger Service Team"))
                        .license(new License().name("Proprietary")));
    }
}
