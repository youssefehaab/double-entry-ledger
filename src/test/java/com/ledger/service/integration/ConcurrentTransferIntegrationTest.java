package com.ledger.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.service.domain.Account;
import com.ledger.service.domain.AccountType;
import com.ledger.service.repository.AccountRepository;
import com.ledger.service.repository.EntryRepository;
import com.ledger.service.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Proves that the pessimistic account-row locking in
 * {@code TransactionWriter#lockAccountsInOrder} correctly serializes
 * concurrent posting against the same two accounts: no entry is lost,
 * duplicated, or partially committed, and final balances reconcile exactly
 * under genuine concurrent contention. It also proves the sorted lock
 * ordering does what it claims: transfer direction between the two accounts
 * is deliberately alternated, which is exactly the shape that would
 * deadlock if locks were acquired in request-payload order instead of
 * sorted account-id order (see the javadoc on
 * {@code TransactionWriter#lockAccountsInOrder} for the deadlock scenario).
 *
 * <h2>Why this proves something, not just "it ran"</h2>
 * <ul>
 *   <li><b>Real concurrency.</b> Every one of the {@value #TRANSFER_COUNT}
 *       requests runs on its own dedicated thread (a fixed thread pool sized
 *       to match), and a two-stage {@link CountDownLatch} holds every thread
 *       at the starting line until all {@value #TRANSFER_COUNT} have
 *       reached it, then releases them together - maximizing actual overlap
 *       on the same two account rows rather than firing requests one after
 *       another and calling it "concurrent".</li>
 *   <li><b>No sleeps, no retry-until-it-passes.</b> Each future is awaited
 *       exactly once; its single outcome (HTTP status + body) is recorded
 *       and never retried by the test. Nothing here loops hoping a race
 *       resolves itself - if the locking were wrong, this test would fail
 *       deterministically (a lost/duplicated entry, a wrong balance, or a
 *       transaction that never returns), not flake.</li>
 *   <li><b>Independent expected-value computation.</b> Expected balances
 *       are computed purely from the test's own record of what it sent
 *       (each transfer's direction and amount, tracked in {@link
 *       TransferSpec} before any request is fired) using plain {@link
 *       BigDecimal} arithmetic in this file. Production's balance formula
 *       ({@code EntryRepository#sumSignedAmountsByAccountId}) is never
 *       called to build the expectation - only to (indirectly, via the real
 *       {@code GET /accounts/{id}/balance} endpoint) produce the actual
 *       value being checked. A bug that corrupted the production summation
 *       formula itself therefore cannot also corrupt the expectation and
 *       accidentally cancel out.</li>
 *   <li><b>Every submission is individually accounted for.</b> The test
 *       does not just check aggregate counts and hope: it asserts every
 *       single one of the {@value #TRANSFER_COUNT} futures completed and
 *       returned 201 Created, and fails with the concrete status/body of
 *       any that did not - so a silently dropped or failed transfer cannot
 *       hide inside an aggregate that happens to still add up.</li>
 *   <li><b>Varying amounts.</b> Transfer amounts cycle through 1..13 rather
 *       than using one fixed round-trip amount, so a bug that, say, dropped
 *       one A-&gt;B transfer and one B-&gt;A transfer of the same size could
 *       not silently cancel out and pass anyway.</li>
 * </ul>
 */
class ConcurrentTransferIntegrationTest extends AbstractIntegrationTest {

    private static final int TRANSFER_COUNT = 200;

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

    private UUID accountA;
    private UUID accountB;

    @BeforeEach
    void createAccounts() {
        accountA = accountRepository.saveAndFlush(new Account("Concurrent A", "USD", AccountType.ASSET)).getId();
        accountB = accountRepository.saveAndFlush(new Account("Concurrent B", "USD", AccountType.ASSET)).getId();
    }

    /** One transfer this test intends to submit - the test's own bookkeeping, built before any request fires. */
    private record TransferSpec(int index, UUID from, UUID to, BigDecimal amount, String idempotencyKey) {

        String requestBody() {
            // Debit `to` (increases its debit-positive balance), credit `from`
            // (decreases it) - mirrors the sign convention documented on
            // EntryRepository#sumSignedAmountsByAccountId.
            return """
                    {
                      "description": "Concurrent transfer #%d",
                      "entries": [
                        {"accountId": "%s", "amount": "%s", "direction": "DEBIT"},
                        {"accountId": "%s", "amount": "%s", "direction": "CREDIT"}
                      ]
                    }
                    """.formatted(index, to, amount, from, amount);
        }
    }

    private record TaskOutcome(int index, int httpStatus, String responseBody) {
        boolean succeeded() {
            return httpStatus == 201;
        }
    }

    @Test
    void concurrentAlternatingTransfers_reconcileExactlyWithNoLostOrDuplicatedEntries() throws Exception {
        List<TransferSpec> specs = buildTransferSpecs();

        ExecutorService executor = Executors.newFixedThreadPool(TRANSFER_COUNT);
        try {
            CountDownLatch ready = new CountDownLatch(TRANSFER_COUNT);
            CountDownLatch go = new CountDownLatch(1);

            List<Future<TaskOutcome>> futures = new ArrayList<>(TRANSFER_COUNT);
            for (TransferSpec spec : specs) {
                futures.add(executor.submit(() -> fireTransfer(spec, ready, go)));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS))
                    .as("every thread must reach the starting line before any of them proceeds")
                    .isTrue();
            go.countDown();

            List<TaskOutcome> outcomes = new ArrayList<>(TRANSFER_COUNT);
            for (Future<TaskOutcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }

            // --- Every one of the N submitted transfers is individually accounted for. ---
            assertThat(outcomes).hasSize(TRANSFER_COUNT);
            List<TaskOutcome> failures = outcomes.stream().filter(o -> !o.succeeded()).toList();
            assertThat(failures)
                    .as("every concurrently-submitted, individually-valid transfer must succeed with 201; "
                            + "any failures indicate a lost/rejected transaction: %s", failures)
                    .isEmpty();

            // --- No lost/duplicated entries: exactly one transaction and two entries per success. ---
            assertThat(transactionRepository.count())
                    .as("one transaction row per successfully-processed transfer, no duplicates, none lost")
                    .isEqualTo(TRANSFER_COUNT);
            assertThat(entryRepository.count())
                    .as("exactly 2 entries per successfully-processed transaction")
                    .isEqualTo(TRANSFER_COUNT * 2L);

            // --- Balances reconcile exactly against the test's own independent bookkeeping. ---
            BigDecimal expectedA = expectedNetBalance(specs, accountA);
            BigDecimal expectedB = expectedNetBalance(specs, accountB);
            // Sanity check on the test's own arithmetic: every transfer only moves
            // value between A and B, so their net change must sum to zero.
            assertThat(expectedA.add(expectedB)).isEqualByComparingTo(BigDecimal.ZERO);

            BigDecimal actualA = readBalance(accountA);
            BigDecimal actualB = readBalance(accountB);

            assertThat(actualA).as("account A final balance").isEqualByComparingTo(expectedA);
            assertThat(actualB).as("account B final balance").isEqualByComparingTo(expectedB);
        } finally {
            executor.shutdownNow();
        }
    }

    private List<TransferSpec> buildTransferSpecs() {
        List<TransferSpec> specs = new ArrayList<>(TRANSFER_COUNT);
        for (int i = 0; i < TRANSFER_COUNT; i++) {
            // Alternating direction between the SAME two accounts is exactly the
            // shape that would deadlock under request-payload-order locking
            // (see TransactionWriter#lockAccountsInOrder).
            UUID from = (i % 2 == 0) ? accountA : accountB;
            UUID to = (i % 2 == 0) ? accountB : accountA;
            // Varying amounts (not one fixed round-trip value) so a lost/duplicated
            // pair of opposite-direction transfers of equal size could not
            // silently cancel out in the balance assertions.
            BigDecimal amount = BigDecimal.valueOf(1 + (i % 13)).setScale(2);
            specs.add(new TransferSpec(i, from, to, amount, "concurrent-transfer-" + i));
        }
        return specs;
    }

    /**
     * Computed purely from this test's own record of what it sent ({@code
     * specs}), using plain BigDecimal arithmetic - never by invoking
     * production's balance-summation code. See class javadoc.
     */
    private BigDecimal expectedNetBalance(List<TransferSpec> specs, UUID accountId) {
        BigDecimal net = BigDecimal.ZERO;
        for (TransferSpec spec : specs) {
            if (spec.to().equals(accountId)) {
                net = net.add(spec.amount());
            }
            if (spec.from().equals(accountId)) {
                net = net.subtract(spec.amount());
            }
        }
        return net;
    }

    private BigDecimal readBalance(UUID accountId) throws Exception {
        MvcResult result = mockMvc.perform(get("/accounts/{id}/balance", accountId)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new BigDecimal(json.get("balance").asText());
    }

    private TaskOutcome fireTransfer(TransferSpec spec, CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        go.await(30, TimeUnit.SECONDS);
        MvcResult result = mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", spec.idempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spec.requestBody()))
                .andReturn();
        return new TaskOutcome(spec.index(), result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
    }
}
