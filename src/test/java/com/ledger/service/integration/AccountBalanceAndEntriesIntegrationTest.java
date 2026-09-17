package com.ledger.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledger.service.domain.Account;
import com.ledger.service.domain.AccountType;
import com.ledger.service.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests of GET /accounts/{id}/balance and GET
 * /accounts/{id}/entries against a real Postgres container. Transactions
 * are posted through the real POST /transactions endpoint (rather than
 * inserted directly) so balances/entries are exercised against data that
 * went through the full validated write path, same as production.
 */
class AccountBalanceAndEntriesIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    private UUID cashAccountId;
    private UUID revenueAccountId;

    @BeforeEach
    void createAccounts() {
        cashAccountId = accountRepository.saveAndFlush(new Account("Cash", "USD", AccountType.ASSET)).getId();
        revenueAccountId = accountRepository.saveAndFlush(new Account("Revenue", "USD", AccountType.EQUITY)).getId();
    }

    private void postTransaction(String idempotencyKey, String description, String amount, boolean cashIsDebit)
            throws Exception {
        String debitAccount = cashIsDebit ? cashAccountId.toString() : revenueAccountId.toString();
        String creditAccount = cashIsDebit ? revenueAccountId.toString() : cashAccountId.toString();
        String body = """
                {
                  "description": "%s",
                  "entries": [
                    {"accountId": "%s", "amount": "%s", "direction": "DEBIT"},
                    {"accountId": "%s", "amount": "%s", "direction": "CREDIT"}
                  ]
                }
                """.formatted(description, debitAccount, amount, creditAccount, amount);

        mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void getBalance_sumsDebitsAndCreditsAcrossMultipleEntries() throws Exception {
        // Cash: +100 (debit), +50 (debit), -30 (credit) => net +120
        postTransaction("bal-key-1", "Sale 1", "100.00", true);
        postTransaction("bal-key-2", "Sale 2", "50.00", true);
        postTransaction("bal-key-3", "Refund", "30.00", false);

        mockMvc.perform(get("/accounts/{id}/balance", cashAccountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(cashAccountId.toString()))
                .andExpect(jsonPath("$.balance").value(120.00));

        // Revenue got the opposite side of each entry: -100, -50, +30 => net -120
        mockMvc.perform(get("/accounts/{id}/balance", revenueAccountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(-120.00));
    }

    @Test
    void getBalance_isZeroForAccountWithNoEntries() throws Exception {
        UUID emptyAccountId =
                accountRepository.saveAndFlush(new Account("Unused", "USD", AccountType.ASSET)).getId();

        mockMvc.perform(get("/accounts/{id}/balance", emptyAccountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(new BigDecimal("0").doubleValue()));
    }

    @Test
    void getBalance_unknownAccountReturns404() throws Exception {
        mockMvc.perform(get("/accounts/{id}/balance", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Account Not Found"));
    }

    @Test
    void getEntries_unknownAccountReturns404() throws Exception {
        mockMvc.perform(get("/accounts/{id}/entries", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Account Not Found"));
    }

    /**
     * Phase 4 bugfix A: a non-UUID {id} previously reached an unhandled
     * {@code MethodArgumentTypeMismatchException} and fell through to the
     * generic {@code Exception.class} handler, returning an undocumented
     * 500 (found by the black-box api-tests suite). {@code
     * GlobalExceptionHandler#handleTypeMismatch} now maps it to a clean 400.
     */
    @Test
    void getBalance_malformedUuidReturns400NotA500() throws Exception {
        mockMvc.perform(get("/accounts/{id}/balance", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Malformed Request"));
    }

    /** Same bugfix as {@link #getBalance_malformedUuidReturns400NotA500()}, for the entries endpoint. */
    @Test
    void getEntries_malformedUuidReturns400NotA500() throws Exception {
        mockMvc.perform(get("/accounts/{id}/entries", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Malformed Request"));
    }

    @Test
    void getEntries_returnsPaginatedResultsWithMetadata() throws Exception {
        // 5 transactions => 5 entries against cashAccountId (one leg each).
        for (int i = 1; i <= 5; i++) {
            postTransaction("page-key-" + i, "Txn " + i, "10.00", true);
        }

        mockMvc.perform(get("/accounts/{id}/entries", cashAccountId)
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));

        mockMvc.perform(get("/accounts/{id}/entries", cashAccountId)
                        .param("page", "2")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.page").value(2));
    }

    @Test
    void getEntries_defaultsToPageSizeTwentyWhenNoParamsGiven() throws Exception {
        postTransaction("default-page-key-1", "Only txn", "5.00", true);

        mockMvc.perform(get("/accounts/{id}/entries", cashAccountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.size").value(20));
    }
}
