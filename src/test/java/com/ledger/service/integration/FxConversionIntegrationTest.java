package com.ledger.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.service.domain.Account;
import com.ledger.service.domain.AccountType;
import com.ledger.service.domain.Entry;
import com.ledger.service.domain.EntryDirection;
import com.ledger.service.repository.AccountRepository;
import com.ledger.service.repository.EntryRepository;
import com.ledger.service.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * End-to-end tests of Phase 1 (v1 -&gt; v1.1) multi-currency posting, through
 * the real {@code POST /transactions} endpoint against a real Postgres
 * container - proving the whole pipeline (account currency lookup, {@code
 * FxRateProvider} conversion, {@code Entry} persistence, app-level balance
 * pre-check, and the V9 DB trigger) works together, not just its individual
 * pieces in isolation.
 *
 * <p>Seeded rates used here come from {@code V7__create_fx_rates_table.sql}
 * (EUR -&gt; USD = 1.08, static/illustrative - see that migration).
 */
class FxConversionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private EntryRepository entryRepository;

    private UUID usdAccountId;
    private UUID eurAccountId;

    @BeforeEach
    void createAccounts() {
        usdAccountId = accountRepository.saveAndFlush(new Account("USD Cash", "USD", AccountType.ASSET)).getId();
        eurAccountId = accountRepository.saveAndFlush(new Account("EUR Cash", "EUR", AccountType.ASSET)).getId();
    }

    /**
     * A transaction whose two legs are in different currencies, but are
     * exactly balanced once converted to the base currency (USD): 100 EUR at
     * the seeded 1.08 rate is exactly 108.00 USD. This would have been
     * wrongly rejected as "unbalanced" by both the old currency-blind
     * app-level pre-check and the old V5 DB trigger (sum(amount): 100 != 108)
     * - proving both were correctly replaced with base-currency-aware checks.
     */
    @Test
    void crossCurrencyPost_producesCorrectBaseCurrencyAmountAndRateUsed() throws Exception {
        String body = """
                {
                  "description": "EUR expense converted to USD base currency",
                  "entries": [
                    {"accountId": "%s", "amount": "100.00", "direction": "DEBIT"},
                    {"accountId": "%s", "amount": "108.00", "direction": "CREDIT"}
                  ]
                }
                """.formatted(eurAccountId, usdAccountId);

        String responseJson = mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", "key-fx-cross-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(responseJson);
        UUID transactionId = UUID.fromString(response.get("id").asText());

        List<Entry> entries = entryRepository.findByTransactionId(transactionId);
        assertThat(entries).hasSize(2);

        Entry eurEntry = entries.stream().filter(e -> e.getAccount().getId().equals(eurAccountId)).findFirst()
                .orElseThrow();
        Entry usdEntry = entries.stream().filter(e -> e.getAccount().getId().equals(usdAccountId)).findFirst()
                .orElseThrow();

        assertThat(eurEntry.getAmount()).isEqualByComparingTo("100.00");
        assertThat(eurEntry.getBaseCurrencyAmount()).isEqualByComparingTo("108.0000");
        assertThat(eurEntry.getFxRateUsed()).isEqualByComparingTo("1.08000000");
        assertThat(eurEntry.getFxRateEffectiveAt()).isNotNull();

        assertThat(usdEntry.getAmount()).isEqualByComparingTo("108.00");
        assertThat(usdEntry.getBaseCurrencyAmount()).isEqualByComparingTo("108.0000");
        // USD -> USD is the identity conversion: rate is exactly 1, never a
        // DB-sourced "1.00000000" row (none is seeded/needed - see
        // DbFxRateProvider).
        assertThat(usdEntry.getFxRateUsed()).isEqualByComparingTo("1");

        // Net base-currency amount balances to exactly zero (108.0000 debit
        // vs 108.0000 credit), even though the native amounts (100.00 vs
        // 108.00) do not - this is exactly the invariant V9 checks.
        BigDecimal signedSum = entries.stream()
                .map(e -> e.getDirection() == EntryDirection.DEBIT
                        ? e.getBaseCurrencyAmount()
                        : e.getBaseCurrencyAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(signedSum).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Confirms the same-currency path is a genuine identity conversion
     * (rate exactly 1, baseCurrencyAmount exactly equal to amount) through
     * the exact same posting path as the cross-currency test above - not a
     * separately-tested special case in the app layer, only short-circuited
     * inside DbFxRateProvider before any DB lookup (see that class's
     * javadoc).
     */
    @Test
    void sameCurrencyPost_isIdentityNoOp() throws Exception {
        UUID otherUsdAccountId =
                accountRepository.saveAndFlush(new Account("USD Revenue", "USD", AccountType.EQUITY)).getId();

        String body = """
                {
                  "description": "Same-currency posting",
                  "entries": [
                    {"accountId": "%s", "amount": "50.00", "direction": "DEBIT"},
                    {"accountId": "%s", "amount": "50.00", "direction": "CREDIT"}
                  ]
                }
                """.formatted(usdAccountId, otherUsdAccountId);

        String responseJson = mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", "key-fx-identity-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID transactionId = UUID.fromString(objectMapper.readTree(responseJson).get("id").asText());
        List<Entry> entries = entryRepository.findByTransactionId(transactionId);
        assertThat(entries).hasSize(2);

        for (Entry entry : entries) {
            assertThat(entry.getBaseCurrencyAmount()).isEqualByComparingTo(entry.getAmount());
            assertThat(entry.getFxRateUsed()).isEqualByComparingTo("1");
        }
    }

    /**
     * No fx_rates row exists for CHF -> USD (not seeded by V7) - this must
     * surface as a clean 422 via {@code UnsupportedCurrencyPairException},
     * never a raw 500, and must persist nothing.
     */
    @Test
    void unsupportedCurrencyPair_returns422AndPersistsNothing() throws Exception {
        UUID chfAccountId =
                accountRepository.saveAndFlush(new Account("CHF Cash", "CHF", AccountType.ASSET)).getId();

        String body = """
                {
                  "description": "Unsupported currency pair",
                  "entries": [
                    {"accountId": "%s", "amount": "10.00", "direction": "DEBIT"},
                    {"accountId": "%s", "amount": "10.00", "direction": "CREDIT"}
                  ]
                }
                """.formatted(chfAccountId, usdAccountId);

        long txCountBefore = transactionRepository.count();
        long entryCountBefore = entryRepository.count();

        mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", "key-fx-unsupported-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Unsupported Currency Pair"))
                .andExpect(jsonPath("$.messages[0]").value(org.hamcrest.Matchers.containsString("CHF")))
                .andExpect(jsonPath("$.messages[0]").value(org.hamcrest.Matchers.containsString("USD")));

        assertThat(transactionRepository.count()).isEqualTo(txCountBefore);
        assertThat(entryRepository.count()).isEqualTo(entryCountBefore);
        assertThat(transactionRepository.findByIdempotencyKey("key-fx-unsupported-1")).isEmpty();
    }
}
