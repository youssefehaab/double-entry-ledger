package com.ledger.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.service.domain.Account;
import com.ledger.service.domain.AccountType;
import com.ledger.service.repository.AccountRepository;
import com.ledger.service.repository.EntryRepository;
import com.ledger.service.repository.TransactionRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end tests of POST /transactions and GET /transactions/{id} against
 * a real Postgres container, through the actual MVC dispatcher.
 */
class TransactionControllerIntegrationTest extends AbstractIntegrationTest {

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

    private UUID cashAccountId;
    private UUID revenueAccountId;

    @BeforeEach
    void createAccounts() {
        cashAccountId = accountRepository.saveAndFlush(new Account("Cash", "USD", AccountType.ASSET)).getId();
        revenueAccountId = accountRepository.saveAndFlush(new Account("Revenue", "USD", AccountType.EQUITY)).getId();
    }

    private String balancedRequestBody(String description, String amount) {
        return """
                {
                  "description": "%s",
                  "entries": [
                    {"accountId": "%s", "amount": "%s", "direction": "DEBIT"},
                    {"accountId": "%s", "amount": "%s", "direction": "CREDIT"}
                  ]
                }
                """.formatted(description, cashAccountId, amount, revenueAccountId, amount);
    }

    @Test
    void createTransaction_persistsBalancedTransactionAndReturns201() throws Exception {
        String body = balancedRequestBody("Invoice #1042 payment", "100.00");

        mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", "key-create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.postedAt").exists())
                .andExpect(jsonPath("$.entries.length()").value(2));

        assertThat(transactionRepository.findByIdempotencyKey("key-create-1")).isPresent();
        UUID txId = transactionRepository.findByIdempotencyKey("key-create-1").get().getId();
        assertThat(entryRepository.findByTransactionId(txId)).hasSize(2);
    }

    @Test
    void createTransaction_rejectsUnbalancedEntriesWith422AndPersistsNothing() throws Exception {
        String body = """
                {
                  "description": "Unbalanced",
                  "entries": [
                    {"accountId": "%s", "amount": "100.00", "direction": "DEBIT"},
                    {"accountId": "%s", "amount": "40.00", "direction": "CREDIT"}
                  ]
                }
                """.formatted(cashAccountId, revenueAccountId);

        long txCountBefore = transactionRepository.count();
        long entryCountBefore = entryRepository.count();

        mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", "key-unbalanced-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Unbalanced Transaction"));

        assertThat(transactionRepository.count()).isEqualTo(txCountBefore);
        assertThat(entryRepository.count()).isEqualTo(entryCountBefore);
        assertThat(transactionRepository.findByIdempotencyKey("key-unbalanced-1")).isEmpty();
    }

    @Test
    void createTransaction_rejectsUnknownAccountWith404() throws Exception {
        UUID unknownAccountId = UUID.randomUUID();
        String body = """
                {
                  "description": "References a missing account",
                  "entries": [
                    {"accountId": "%s", "amount": "50.00", "direction": "DEBIT"},
                    {"accountId": "%s", "amount": "50.00", "direction": "CREDIT"}
                  ]
                }
                """.formatted(unknownAccountId, revenueAccountId);

        mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", "key-missing-account-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Account Not Found"));

        assertThat(transactionRepository.findByIdempotencyKey("key-missing-account-1")).isEmpty();
    }

    @Test
    void createTransaction_missingIdempotencyKeyHeaderReturns400() throws Exception {
        String body = balancedRequestBody("No key supplied", "10.00");

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTransaction_blankIdempotencyKeyHeaderReturns400() throws Exception {
        String body = balancedRequestBody("Blank key supplied", "10.00");

        mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTransaction_replayWithSameKeyAndSameBodyReturns200AndDoesNotDuplicate() throws Exception {
        String body = balancedRequestBody("Replayed invoice", "75.00");
        String idempotencyKey = "key-replay-1";

        String firstResponseJson = mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode firstResponse = objectMapper.readTree(firstResponseJson);
        UUID firstTransactionId = UUID.fromString(firstResponse.get("id").asText());

        long txCountAfterFirst = transactionRepository.count();
        long entryCountAfterFirst = entryRepository.count();

        String secondResponseJson = mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode secondResponse = objectMapper.readTree(secondResponseJson);

        assertThat(secondResponse.get("id").asText()).isEqualTo(firstTransactionId.toString());
        assertThat(transactionRepository.count()).isEqualTo(txCountAfterFirst);
        assertThat(entryRepository.count()).isEqualTo(entryCountAfterFirst);
        assertThat(entryRepository.findByTransactionId(firstTransactionId)).hasSize(2);
    }

    @Test
    void createTransaction_sameKeyDifferentBodyReturns409AndDoesNotMutateOriginal() throws Exception {
        String idempotencyKey = "key-conflict-1";
        String originalBody = balancedRequestBody("Original description", "20.00");

        String firstResponseJson = mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(originalBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID originalTransactionId =
                UUID.fromString(objectMapper.readTree(firstResponseJson).get("id").asText());

        String differentBody = balancedRequestBody("A completely different description", "999.00");

        mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(differentBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Idempotency Key Conflict"));

        // Original transaction must be untouched.
        mockMvc.perform(get("/transactions/{id}", originalTransactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Original description"));
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    void getTransaction_returnsDetailWithEntries() throws Exception {
        String body = balancedRequestBody("Detail lookup", "33.33");
        String createResponseJson = mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", "key-detail-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(createResponseJson).get("id").asText());

        mockMvc.perform(get("/transactions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.description").value("Detail lookup"))
                .andExpect(jsonPath("$.entries.length()").value(2));
    }

    @Test
    void getTransaction_unknownIdReturns404() throws Exception {
        mockMvc.perform(get("/transactions/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Transaction Not Found"));
    }

    /**
     * Phase 4 bugfix A: a non-UUID {id} previously reached an unhandled
     * {@code MethodArgumentTypeMismatchException} and fell through to the
     * generic {@code Exception.class} handler, returning an undocumented
     * 500 (found by the black-box api-tests suite). {@code
     * GlobalExceptionHandler#handleTypeMismatch} now maps it to a clean 400.
     */
    @Test
    void getTransaction_malformedUuidReturns400NotA500() throws Exception {
        mockMvc.perform(get("/transactions/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Malformed Request"));
    }

    /**
     * Phase 4 bugfix C: an amount whose precision/scale exceeds the
     * {@code entries.amount NUMERIC(19,4)} column previously reached the DB,
     * failed with a numeric-overflow {@code DataIntegrityViolationException},
     * and was mapped by the generic fallback handler to 409 - the same
     * status documented for "Idempotency-Key reused with a different body",
     * which would mislead a client into treating this as a safe-to-refetch
     * idempotent conflict. {@code EntryRequest.amount}'s new
     * {@code @Digits(integer = 15, fraction = 4)} constraint now catches
     * this before any DB call, returning 400 - never a 409 - and no
     * transaction is persisted.
     */
    @Test
    void createTransaction_implausiblyLargeAmountReturns400NotConflict() throws Exception {
        String body = """
                {
                  "description": "huge amount",
                  "entries": [
                    {"accountId": "%s", "amount": "99999999999999999999999999999", "direction": "DEBIT"},
                    {"accountId": "%s", "amount": "99999999999999999999999999999", "direction": "CREDIT"}
                  ]
                }
                """.formatted(cashAccountId, revenueAccountId);

        mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", "key-huge-amount-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"));

        assertThat(transactionRepository.findByIdempotencyKey("key-huge-amount-1")).isEmpty();
        assertThat(transactionRepository.count()).isZero();
    }

    /**
     * Concurrent-replay race: two requests with the same brand-new
     * Idempotency-Key and the same body arrive "simultaneously" (both pass
     * the read-before-write check before either has committed). The
     * uq_transactions_idempotency_key UNIQUE constraint guarantees at most
     * one INSERT succeeds; the loser must be recovered into a clean
     * response (200 or 201, matched against the winner), never a raw 500,
     * and exactly one Transaction/pair-of-Entries must exist afterward.
     */
    @Test
    void createTransaction_concurrentRequestsWithSameNewKeyResultInExactlyOneTransaction() throws Exception {
        String idempotencyKey = "key-race-1";
        String body = balancedRequestBody("Concurrent replay race", "42.00");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            List<Future<MvcResult>> futures = List.of(
                    executor.submit(() -> fireRequest(idempotencyKey, body, ready, go)),
                    executor.submit(() -> fireRequest(idempotencyKey, body, ready, go)));

            ready.await(10, TimeUnit.SECONDS);
            go.countDown();

            List<MvcResult> results = futures.stream().map(f -> {
                try {
                    return f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();

            for (MvcResult result : results) {
                int status = result.getResponse().getStatus();
                assertThat(status).as("neither concurrent request should ever raw-fail")
                        .isIn(200, 201);
            }

            Set<String> returnedTransactionIds = results.stream()
                    .map(r -> {
                        try {
                            return objectMapper.readTree(r.getResponse().getContentAsString())
                                    .get("id").asText();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toSet());

            assertThat(returnedTransactionIds)
                    .as("both requests must resolve to the same, single transaction")
                    .hasSize(1);
            assertThat(transactionRepository.findByIdempotencyKey(idempotencyKey)).isPresent();
            assertThat(transactionRepository.count()).isEqualTo(1);
            UUID onlyTransactionId = transactionRepository.findByIdempotencyKey(idempotencyKey).get().getId();
            assertThat(entryRepository.findByTransactionId(onlyTransactionId)).hasSize(2);
        } finally {
            executor.shutdownNow();
        }
    }

    private MvcResult fireRequest(String idempotencyKey, String body, CountDownLatch ready, CountDownLatch go)
            throws Exception {
        ready.countDown();
        go.await(10, TimeUnit.SECONDS);
        return mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }
}
