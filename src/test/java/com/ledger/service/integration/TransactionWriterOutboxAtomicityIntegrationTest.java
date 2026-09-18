package com.ledger.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.service.domain.Account;
import com.ledger.service.domain.AccountType;
import com.ledger.service.domain.OutboxEvent;
import com.ledger.service.domain.OutboxStatus;
import com.ledger.service.repository.AccountRepository;
import com.ledger.service.repository.EntryRepository;
import com.ledger.service.repository.OutboxEventRepository;
import com.ledger.service.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves {@code TransactionWriter#createAndPersist} inserts exactly one
 * {@code outbox_events} row atomically with the {@code Transaction}/{@code
 * Entry} rows it describes - the entire point of the outbox pattern (see
 * that method's javadoc).
 *
 * <p>Two complementary proofs, not one:
 * <ul>
 *   <li>{@link #postingBalancedTransaction_insertsExactlyOneOutboxRow_withCorrectPayload()} -
 *       the successful path: when entries commit, exactly one PENDING
 *       outbox row commits with them, with a payload that actually matches
 *       what was posted.</li>
 *   <li>{@link #failedPost_rollsBackEntriesAndTheOutboxRowTogether()} -
 *       the forced-rollback path called out explicitly in this phase's
 *       scope: an unsupported currency pair fails {@code createAndPersist}
 *       before it returns, and because the outbox insert lives inside the
 *       exact same {@code @Transactional} method as the entries insert,
 *       nothing commits - not the transaction, not the entries, and not an
 *       outbox row either. If the outbox insert were a separate
 *       transaction/call instead (the dual-write bug this pattern exists to
 *       prevent), this test would catch that regression: a naive
 *       "always insert an outbox row, even after failure" implementation
 *       would leave a stray row behind here.</li>
 * </ul>
 */
class TransactionWriterOutboxAtomicityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private EntryRepository entryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private UUID usdAccountId;
    private UUID eurAccountId;

    @BeforeEach
    void createAccounts() {
        usdAccountId = accountRepository.saveAndFlush(new Account("USD Cash", "USD", AccountType.ASSET)).getId();
        eurAccountId = accountRepository.saveAndFlush(new Account("EUR Cash", "EUR", AccountType.ASSET)).getId();
    }

    @Test
    void postingBalancedTransaction_insertsExactlyOneOutboxRow_withCorrectPayload() throws Exception {
        String body = """
                {
                  "description": "Outbox atomicity happy path",
                  "entries": [
                    {"accountId": "%s", "amount": "100.00", "direction": "DEBIT"},
                    {"accountId": "%s", "amount": "108.00", "direction": "CREDIT"}
                  ]
                }
                """.formatted(eurAccountId, usdAccountId);

        String responseJson = mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", "key-outbox-atomicity-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID transactionId = UUID.fromString(objectMapper.readTree(responseJson).get("id").asText());

        List<OutboxEvent> outboxRows = outboxEventRepository.findAll();
        assertThat(outboxRows).hasSize(1);

        OutboxEvent row = outboxRows.get(0);
        assertThat(row.getAggregateType()).isEqualTo("TRANSACTION");
        assertThat(row.getAggregateId()).isEqualTo(transactionId);
        assertThat(row.getEventType()).isEqualTo("TransactionPosted");
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttemptCount()).isZero();
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getCreatedAt()).isNotNull();

        JsonNode payload = objectMapper.readTree(row.getPayload());
        assertThat(payload.get("transactionId").asText()).isEqualTo(transactionId.toString());
        assertThat(payload.get("idempotencyKey").asText()).isEqualTo("key-outbox-atomicity-1");
        assertThat(payload.get("status").asText()).isEqualTo("POSTED");
        assertThat(payload.get("postedAt").asText()).isNotBlank();

        JsonNode entries = payload.get("entries");
        assertThat(entries).hasSize(2);
        for (JsonNode entry : entries) {
            assertThat(entry.get("accountId").asText()).isIn(eurAccountId.toString(), usdAccountId.toString());
            assertThat(entry.has("amount")).isTrue();
            assertThat(entry.has("currency")).isTrue();
            assertThat(entry.has("direction")).isTrue();
            assertThat(entry.has("baseCurrencyAmount")).isTrue();
            assertThat(entry.has("fxRateUsed")).isTrue();
            if (entry.get("accountId").asText().equals(eurAccountId.toString())) {
                assertThat(entry.get("currency").asText()).isEqualTo("EUR");
                // Compared numerically (isEqualByComparingTo), not as exact
                // JSON text: Postgres's jsonb column type canonicalizes
                // numeric literals on storage (e.g. an inserted "108.0000"
                // can read back as "108.0" - same value, different
                // formatting), per Postgres's own documented jsonb behavior.
                // The VALUE survives a jsonb round trip exactly; the exact
                // textual scale/trailing-zero formatting does not - a real
                // downstream consumer of this payload must compare/parse
                // these as arbitrary-precision decimals by value, not rely
                // on a fixed number of decimal places.
                assertThat(new BigDecimal(entry.get("baseCurrencyAmount").asText()))
                        .isEqualByComparingTo("108.0000");
                assertThat(new BigDecimal(entry.get("fxRateUsed").asText()))
                        .isEqualByComparingTo("1.08000000");
            } else {
                assertThat(entry.get("currency").asText()).isEqualTo("USD");
            }
        }
    }

    @Test
    void failedPost_rollsBackEntriesAndTheOutboxRowTogether() throws Exception {
        UUID chfAccountId =
                accountRepository.saveAndFlush(new Account("CHF Cash", "CHF", AccountType.ASSET)).getId();

        String body = """
                {
                  "description": "Unsupported currency pair - must roll back everything, outbox included",
                  "entries": [
                    {"accountId": "%s", "amount": "10.00", "direction": "DEBIT"},
                    {"accountId": "%s", "amount": "10.00", "direction": "CREDIT"}
                  ]
                }
                """.formatted(chfAccountId, usdAccountId);

        long txCountBefore = transactionRepository.count();
        long entryCountBefore = entryRepository.count();
        long outboxCountBefore = outboxEventRepository.count();

        mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", "key-outbox-atomicity-rollback-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());

        assertThat(transactionRepository.count()).isEqualTo(txCountBefore);
        assertThat(entryRepository.count()).isEqualTo(entryCountBefore);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCountBefore);
        assertThat(transactionRepository.findByIdempotencyKey("key-outbox-atomicity-rollback-1")).isEmpty();
    }
}
