package com.ledger.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.service.domain.Account;
import com.ledger.service.domain.AccountType;
import com.ledger.service.domain.OutboxEvent;
import com.ledger.service.domain.OutboxStatus;
import com.ledger.service.repository.AccountRepository;
import com.ledger.service.repository.OutboxEventRepository;
import com.ledger.service.service.outbox.OutboxRelay;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration coverage for {@link OutboxRelay} against a real Kafka broker
 * (Testcontainers, see {@link AbstractKafkaIntegrationTest}) - no mocked
 * {@code KafkaTemplate}/producer/consumer anywhere in this class, matching
 * this project's no-mocks-for-infrastructure convention.
 */
class OutboxRelayIntegrationTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxRelay outboxRelay;

    private UUID usdAccountId;
    private UUID eurAccountId;

    @BeforeEach
    void createAccounts() {
        usdAccountId = accountRepository.saveAndFlush(new Account("USD Cash", "USD", AccountType.ASSET)).getId();
        eurAccountId = accountRepository.saveAndFlush(new Account("EUR Cash", "EUR", AccountType.ASSET)).getId();
    }

    /**
     * End-to-end: real HTTP POST -&gt; TransactionWriter's atomic outbox
     * insert -&gt; OutboxRelay publish -&gt; real Kafka broker -&gt; a real
     * consumer reads the exact JSON payload back and it matches what was
     * posted. This is the proof-of-pipeline the tester agent's own tests
     * can build on with confidence that the mechanism underneath actually
     * works.
     */
    @Test
    void postedTransaction_isRelayedToKafka_andConsumableWithCorrectPayload() throws Exception {
        String body = """
                {
                  "description": "Relay end-to-end",
                  "entries": [
                    {"accountId": "%s", "amount": "100.00", "direction": "DEBIT"},
                    {"accountId": "%s", "amount": "108.00", "direction": "CREDIT"}
                  ]
                }
                """.formatted(eurAccountId, usdAccountId);

        String responseJson = mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", "key-relay-e2e-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID transactionId = UUID.fromString(objectMapper.readTree(responseJson).get("id").asText());

        // Call the relay deterministically instead of waiting on
        // @Scheduled's poll interval - this test is about relay
        // correctness, not scheduling.
        boolean processed = outboxRelay.relayNextEligibleEvent();
        assertThat(processed).isTrue();

        OutboxEvent row = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(transactionId))
                .findFirst().orElseThrow();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(row.getPublishedAt()).isNotNull();
        assertThat(row.getAttemptCount()).isZero();

        try (KafkaConsumer<String, String> consumer = newConsumer("relay-e2e-" + UUID.randomUUID())) {
            consumer.subscribe(List.of(OUTBOX_TOPIC));
            ConsumerRecord<String, String> record = pollForRecordWithKey(consumer, transactionId.toString());

            assertThat(record.key()).isEqualTo(transactionId.toString());
            JsonNode payload = objectMapper.readTree(record.value());
            assertThat(payload.get("transactionId").asText()).isEqualTo(transactionId.toString());
            assertThat(payload.get("idempotencyKey").asText()).isEqualTo("key-relay-e2e-1");
            assertThat(payload.get("entries")).hasSize(2);
        }
    }

    /**
     * SKIP LOCKED under real concurrency: N PENDING rows, multiple threads
     * draining the same {@code OutboxRelay} bean concurrently. Proves - by
     * both DB state and actual Kafka delivery counts - that every row is
     * published exactly once, never zero times (stuck) and never more than
     * once (double-published), even though multiple relay passes are
     * racing each other against the same rows at the same time.
     */
    @Test
    void concurrentRelayPasses_neverDoublePublishTheSameRow() throws Exception {
        int rowCount = 12;
        int threadCount = 4;
        List<UUID> aggregateIds = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            UUID aggregateId = UUID.randomUUID();
            aggregateIds.add(aggregateId);
            String payload = "{\"transactionId\":\"" + aggregateId + "\",\"seq\":" + i + "}";
            outboxEventRepository.save(new OutboxEvent("TRANSACTION", aggregateId, "TransactionPosted", payload));
        }
        outboxEventRepository.flush();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(CompletableFuture.runAsync(() -> {
                    // Each worker drains the shared queue until nothing
                    // eligible is left for it - SKIP LOCKED is what makes
                    // this safe to run from multiple threads/transactions
                    // at once against the same underlying rows.
                    while (Boolean.TRUE.equals(outboxRelay.relayNextEligibleEvent())) {
                        // keep draining
                    }
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        List<OutboxEvent> rows = outboxEventRepository.findAll();
        assertThat(rows).hasSize(rowCount);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(row.getAttemptCount()).isZero();
        });

        // Independent confirmation via the real broker: each of THIS test's
        // aggregateIds was published exactly once - if SKIP LOCKED had
        // failed to prevent a double-publish, one of these keys would show
        // a count > 1. Deliberately not asserting the topic's full key set
        // (other test methods in this class/hierarchy share the same
        // singleton Kafka container and topic - see
        // AbstractKafkaIntegrationTest - so unrelated keys from other tests
        // may also be present; that is expected and not this test's
        // concern).
        Set<String> expectedKeys = aggregateIds.stream().map(UUID::toString).collect(Collectors.toSet());
        Map<String, Integer> countsByKey = new ConcurrentHashMap<>();
        try (KafkaConsumer<String, String> consumer = newConsumer("relay-concurrency-" + UUID.randomUUID())) {
            consumer.subscribe(List.of(OUTBOX_TOPIC));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    countsByKey.merge(record.key(), 1, Integer::sum);
                }
                Map<String, Integer> relevantCounts = new HashMap<>(countsByKey);
                relevantCounts.keySet().retainAll(expectedKeys);
                assertThat(relevantCounts.keySet()).containsExactlyInAnyOrderElementsOf(expectedKeys);
            });
        }
        Map<String, Integer> relevantCounts = new HashMap<>(countsByKey);
        relevantCounts.keySet().retainAll(expectedKeys);
        assertThat(relevantCounts.values()).allSatisfy(count -> assertThat(count).isEqualTo(1));
    }

    /**
     * Bounded dead-letter path: a row that can never be published (pointed,
     * via a deliberately misconfigured {@code KafkaTemplate}, at an
     * unreachable broker) is retried up to {@code maxAttempts} times and
     * then marked FAILED - never retried again after that, proving the
     * relay does not spin on a permanently-broken row forever. Uses a
     * hand-built {@code OutboxRelay} (not the Spring-managed bean, which is
     * wired to the real Testcontainers broker) with tight producer
     * timeouts and zero backoff so this test completes quickly without
     * needing to manipulate the clock.
     */
    @Test
    void permanentlyFailingPublish_isDeadLetteredAfterMaxAttempts_andNeverRetriedAgain() {
        UUID aggregateId = UUID.randomUUID();
        String payload = "{\"transactionId\":\"" + aggregateId + "\"}";
        UUID outboxRowId = outboxEventRepository.saveAndFlush(
                new OutboxEvent("TRANSACTION", aggregateId, "TransactionPosted", payload)).getId();

        int maxAttempts = 3;
        Map<String, Object> producerProps = new HashMap<>();
        // Reserved/unroutable-in-practice address: nothing listens here, so
        // connection attempts fail fast rather than hanging.
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
        producerProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
        producerProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2000);
        producerProps.put(ProducerConfig.RETRIES_CONFIG, 0);

        DefaultKafkaProducerFactory<String, String> brokenProducerFactory =
                new DefaultKafkaProducerFactory<>(producerProps);
        try {
            KafkaTemplate<String, String> brokenTemplate = new KafkaTemplate<>(brokenProducerFactory);
            OutboxRelay brokenRelay = new OutboxRelay(
                    outboxEventRepository,
                    brokenTemplate,
                    OUTBOX_TOPIC,
                    /* batchSize */ 1,
                    maxAttempts,
                    /* backoffBaseSeconds */ 0,
                    /* backoffMaxSeconds */ 0,
                    /* kafkaSendTimeoutMs */ 2000);

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                boolean processed = brokenRelay.relayNextEligibleEvent();
                assertThat(processed)
                        .as("attempt %d should have found the still-PENDING row", attempt)
                        .isTrue();

                OutboxEvent row = outboxEventRepository.findById(outboxRowId).orElseThrow();
                assertThat(row.getAttemptCount()).isEqualTo(attempt);
                if (attempt < maxAttempts) {
                    assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
                } else {
                    assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
                }
            }

            // One more call after exhausting max attempts: the row is now
            // FAILED, not PENDING, so the eligibility query must no longer
            // select it - the relay must not spin on it forever.
            boolean processedAfterDeadLetter = brokenRelay.relayNextEligibleEvent();
            assertThat(processedAfterDeadLetter).isFalse();

            OutboxEvent finalRow = outboxEventRepository.findById(outboxRowId).orElseThrow();
            assertThat(finalRow.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(finalRow.getAttemptCount()).isEqualTo(maxAttempts);
            assertThat(finalRow.getPublishedAt()).isNull();
        } finally {
            brokenProducerFactory.destroy();
        }
    }

    /**
     * Gate 2 follow-up (flagged non-blocking on Phase 2's review, assigned
     * to Phase 3): proves {@link OutboxEventRepository#lockNextEligibleForRelay}'s
     * backoff WHERE clause actually excludes a PENDING row whose backoff
     * window has not yet elapsed - not just that the reviewer read the SQL
     * and judged it correct. {@link #permanentlyFailingPublish_isDeadLetteredAfterMaxAttempts_andNeverRetriedAgain()}
     * does not cover this: it deliberately configures {@code
     * backoffBaseSeconds=0}/{@code backoffMaxSeconds=0} on a hand-built
     * relay so every attempt is immediately eligible again, sidestepping
     * real backoff entirely.
     *
     * <p>Uses the real, Spring-managed {@code outboxRelay} bean (same as
     * {@link #postedTransaction_isRelayedToKafka_andConsumableWithCorrectPayload()}
     * above), which is wired to this project's actual default backoff
     * config ({@code ledger.outbox.backoff-base-seconds=30}, {@code
     * backoff-max-seconds=900} - see {@code application.yml}; nothing in
     * this test hierarchy overrides them) - so this exercises the exact
     * formula and the exact config production runs with, not a
     * test-convenient stand-in.
     *
     * <p>Two rows are created in the same batch, both already having failed
     * once ({@code attempt_count = 1}, via the real {@code
     * OutboxEvent#recordFailedAttempt} business method - not a raw column
     * UPDATE - so {@code last_attempt_at} is set exactly the way production
     * sets it):
     * <ul>
     *   <li>{@code notYetEligibleEvent}: {@code last_attempt_at} = now. With
     *       {@code attempt_count = 1}, the required backoff is
     *       {@code min(900, 30 * 2^1) = 60} seconds - nowhere near elapsed -
     *       so this row must NOT be selected.</li>
     *   <li>{@code eligibleEvent}: {@code last_attempt_at} = 2 hours ago -
     *       far past the same 60-second window - so this row MUST be
     *       selected. This is the proof that the query genuinely matches
     *       something (i.e. the WHERE clause is not silently excluding
     *       everything, which would let the first assertion pass for the
     *       wrong reason).</li>
     * </ul>
     *
     * <p>The first {@link OutboxRelay#relayNextEligibleEvent()} call must
     * pick up {@code eligibleEvent} only (published via the real
     * Testcontainers Kafka broker, same as every other test in this class -
     * no mocking of the send). The second call, with nothing else eligible,
     * must return {@code false}. {@code notYetEligibleEvent} is then
     * re-read fresh from the database and asserted to be untouched: still
     * PENDING, {@code attempt_count} still exactly 1, {@code
     * last_attempt_at} unchanged - a real negative proof that the relay
     * actually attempted a select against this row's batch and skipped it,
     * not merely that this row was never referenced by the test.
     */
    @Test
    void rowWithinBackoffWindow_isSkipped_whileGenuinelyEligibleRowInSameBatchIsPublished() throws Exception {
        // Truncated to microseconds: Postgres TIMESTAMPTZ has microsecond
        // precision, so comparing a re-read value against a
        // nanosecond-precision Instant.now() later in this test would risk
        // a spurious mismatch purely from precision truncation on the
        // round trip through the DB, not from any real behavior difference.
        Instant justNow = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant twoHoursAgo = justNow.minus(2, ChronoUnit.HOURS);

        UUID notYetEligibleAggregateId = UUID.randomUUID();
        OutboxEvent notYetEligibleEvent = outboxEventRepository.saveAndFlush(new OutboxEvent(
                "TRANSACTION", notYetEligibleAggregateId, "TransactionPosted",
                "{\"transactionId\":\"" + notYetEligibleAggregateId + "\"}"));
        // Large maxAttempts so this stays PENDING (not dead-lettered) - only
        // last_attempt_at/attempt_count matter for this test.
        notYetEligibleEvent.recordFailedAttempt(justNow, /* maxAttempts */ 999);
        outboxEventRepository.saveAndFlush(notYetEligibleEvent);
        UUID notYetEligibleRowId = notYetEligibleEvent.getId();

        UUID eligibleAggregateId = UUID.randomUUID();
        OutboxEvent eligibleEvent = outboxEventRepository.saveAndFlush(new OutboxEvent(
                "TRANSACTION", eligibleAggregateId, "TransactionPosted",
                "{\"transactionId\":\"" + eligibleAggregateId + "\"}"));
        eligibleEvent.recordFailedAttempt(twoHoursAgo, /* maxAttempts */ 999);
        outboxEventRepository.saveAndFlush(eligibleEvent);

        // Both rows are PENDING with attempt_count 1 at this point - only
        // last_attempt_at differs, so this isolates exactly the WHERE
        // clause's time-based branch, not the "never attempted" NULL branch.
        assertThat(notYetEligibleEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(eligibleEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);

        boolean firstCallProcessedSomething = outboxRelay.relayNextEligibleEvent();
        assertThat(firstCallProcessedSomething)
                .as("the genuinely-eligible row (2h-old last_attempt_at) must be picked up")
                .isTrue();

        OutboxEvent eligibleAfterFirstCall = outboxEventRepository.findById(eligibleEvent.getId()).orElseThrow();
        assertThat(eligibleAfterFirstCall.getStatus())
                .as("the genuinely-eligible row must actually have been published, proving the query "
                        + "isn't just matching nothing")
                .isEqualTo(OutboxStatus.PUBLISHED);

        boolean secondCallProcessedSomething = outboxRelay.relayNextEligibleEvent();
        assertThat(secondCallProcessedSomething)
                .as("nothing else should be eligible: the only remaining PENDING row is still within its "
                        + "backoff window")
                .isFalse();

        OutboxEvent notYetEligibleAfterRelayCalls = outboxEventRepository.findById(notYetEligibleRowId).orElseThrow();
        assertThat(notYetEligibleAfterRelayCalls.getStatus())
                .as("a row within its backoff window must be left completely untouched by the relay - "
                        + "still PENDING, never selected, never published, never re-attempted")
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(notYetEligibleAfterRelayCalls.getAttemptCount())
                .as("attempt_count must be unchanged - the relay never even locked this row")
                .isEqualTo(1);
        assertThat(notYetEligibleAfterRelayCalls.getLastAttemptAt())
                .as("last_attempt_at must be unchanged - no new attempt was recorded against this row")
                .isEqualTo(justNow);
    }

    private KafkaConsumer<String, String> newConsumer(String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    private ConsumerRecord<String, String> pollForRecordWithKey(KafkaConsumer<String, String> consumer, String key) {
        AtomicInteger attempts = new AtomicInteger();
        Map<String, ConsumerRecord<String, String>> found = new ConcurrentHashMap<>();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            attempts.incrementAndGet();
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                found.put(record.key(), record);
            }
            assertThat(found).containsKey(key);
        });
        return found.get(key);
    }
}
