package com.ledger.apitests.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static com.ledger.apitests.support.TestData.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Black-box proof of the v1.1 outbox -&gt; Kafka event-publishing pipeline: every successfully
 * POSTed transaction (201, a real DB commit) must, asynchronously via {@code OutboxRelay} (default
 * poll interval 2000ms - see {@code ledger.outbox.poll-interval-ms}), result in exactly one message
 * on the real {@code transaction-posted-events} Kafka topic.
 *
 * <p><b>Connectivity / configuration:</b> points at {@code localhost:${KAFKA_PORT:-9092}} by
 * default (matching the docker-compose {@code kafka} service's host-exposed
 * {@code PLAINTEXT_HOST} listener), overridable the same way {@code ApiClient.BASE_URI} is
 * overridable - via a system property first, then an environment variable, then a hardcoded
 * default - so this stays consistent with this suite's existing configurability convention.
 *
 * <p><b>Consumer group / offset-reset judgment call (flagged for backend-reviewer's awareness,
 * not a gate on its own):</b> each test uses a FRESH, randomly-suffixed consumer group
 * ({@code api-tests-<uuid>}) with {@code auto.offset.reset=earliest}. Fresh group -&gt; no
 * cross-test-run partition-assignment/offset contention (a shared, fixed group id would race
 * different suite runs, or resume from a stale committed offset and potentially skip the very
 * record a run is waiting for). {@code earliest} -&gt; a brand-new group with no committed
 * offsets always starts from the beginning of the topic rather than only seeing records
 * produced after subscription - this is required here (a `latest`-starting consumer could easily
 * miss the message if the OutboxRelay's ~2s poll tick publishes it before the consumer finishes
 * joining the group/completing partition assignment, which is a real, not merely theoretical,
 * race against a `latest` consumer). The cost is that a test must scan from the start of the
 * topic (3 partitions, per kafka-init) until it finds the record whose payload's
 * {@code transactionId} matches the transaction this test itself just posted - acceptable at this
 * suite's scale/lifetime (a topic that lives only as long as one docker-compose stack's
 * lifetime), but would need reconsideration (e.g. a longer-lived shared topic in a real CI
 * environment where this suite runs repeatedly against the same long-lived broker) if the topic
 * ever accumulates a large backlog across many suite runs.
 */
@Epic("Double-Entry Ledger API")
@Feature("Outbox -> Kafka event publishing (v1.1) - transaction-posted-events")
class TransactionPostedEventApiTest {

    private static final String BOOTSTRAP_SERVERS = System.getProperty(
            "kafka.bootstrapServers",
            System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:" + System.getProperty(
                    "kafka.port", System.getenv().getOrDefault("KAFKA_PORT", "9092"))));

    private static final String TOPIC = System.getProperty(
            "kafka.topic", System.getenv().getOrDefault("KAFKA_TOPIC", "transaction-posted-events"));

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(20);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KafkaConsumer<String, String> consumer;

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close(Duration.ofSeconds(5));
        }
    }

    @Test
    @Story("Outbox relay -> Kafka delivery")
    @Description("A successfully posted transaction (201) produces exactly one transaction-posted-events "
            + "Kafka message within a reasonable poll timeout (20s - the relay's default poll interval is "
            + "~2s, so this is generous headroom, not an immediate single-poll check). The message key is "
            + "the transaction id, and the JSON payload's transactionId/idempotencyKey/status/entries match "
            + "what was actually posted (see TransactionPostedEventPayload for the exact wire shape).")
    void postedTransactionProducesKafkaEvent() {
        String accountA = createAccount();
        String accountB = createAccount();
        String idempotencyKey = randomIdempotencyKey();
        String description = "kafka outbox proof " + uniqueSuffix();

        Response response = postTransaction(
                idempotencyKey, simpleTransfer(accountA, accountB, new BigDecimal("25.00"), description));
        response.then().statusCode(201);
        String transactionId = response.jsonPath().getString("id");

        consumer = newConsumer();
        consumer.subscribe(List.of(TOPIC));

        ConsumerRecord<String, String> match = pollForRecordMatchingTransaction(consumer, transactionId, POLL_TIMEOUT);

        assertThat("Expected a transaction-posted-events Kafka message for transaction " + transactionId
                        + " (idempotency key '" + idempotencyKey + "', description '" + description + "') within "
                        + POLL_TIMEOUT.toSeconds() + "s of receiving the 201 response, but none arrived. The "
                        + "OutboxRelay polls every ~2s by default (ledger.outbox.poll-interval-ms), so a 20s "
                        + "timeout is well beyond normal delivery latency - this indicates a real gap in the "
                        + "outbox-relay-Kafka pipeline (e.g. the relay is not running, the topic/broker is "
                        + "unreachable from this test, or the outbox row was never inserted), not just a slow poll.",
                match, notNullValue());

        assertThat("Kafka message key must be the transaction id (OutboxRelay keys sends by aggregate_id)",
                match.key(), equalTo(transactionId));

        JsonNode payload = parsePayload(match, transactionId);
        assertThat("payload.transactionId must match the posted transaction",
                payload.path("transactionId").asText(null), equalTo(transactionId));
        assertThat("payload.idempotencyKey must match the Idempotency-Key header used to post the transaction",
                payload.path("idempotencyKey").asText(null), equalTo(idempotencyKey));
        assertThat("payload.status must be POSTED for a successfully committed transaction",
                payload.path("status").asText(null), equalTo("POSTED"));
        assertThat("payload.postedAt must be present", payload.hasNonNull("postedAt"), is(true));
        assertThat("payload.entries must be a 2-element array (one per leg of simpleTransfer)",
                payload.path("entries").isArray(), is(true));
        assertThat(payload.path("entries").size(), equalTo(2));

        for (JsonNode entryNode : payload.path("entries")) {
            assertThat("each entry payload must carry its native currency (not just the base-currency amount) "
                            + "so a downstream consumer never has to call back into this service to learn it",
                    entryNode.path("currency").asText(null), not(emptyOrNullString()));
            assertThat(entryNode.path("accountId").asText(null), not(emptyOrNullString()));
            assertThat(entryNode.path("baseCurrencyAmount").isMissingNode(), is(false));
            assertThat(entryNode.path("fxRateUsed").isMissingNode(), is(false));
        }
    }

    private static JsonNode parsePayload(ConsumerRecord<String, String> record, String transactionId) {
        try {
            return MAPPER.readTree(record.value());
        } catch (Exception e) {
            throw new AssertionError("Kafka message matched on transaction id " + transactionId
                    + " but its value was not valid JSON: " + record.value(), e);
        }
    }

    /**
     * Polls in a loop (never a single blind {@code poll()} call, which can legitimately return zero
     * records even though more are on the way - e.g. mid rebalance, or simply before the relay's next
     * ~2s tick has run) until either a record whose payload's {@code transactionId} matches, or the
     * overall timeout elapses.
     */
    private static ConsumerRecord<String, String> pollForRecordMatchingTransaction(
            KafkaConsumer<String, String> consumer, String transactionId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
            for (ConsumerRecord<String, String> record : records) {
                try {
                    JsonNode node = MAPPER.readTree(record.value());
                    if (transactionId.equals(node.path("transactionId").asText(null))) {
                        return record;
                    }
                } catch (Exception ignored) {
                    // Not JSON, or not this event's shape - keep scanning; a malformed/unrelated
                    // record on the topic must not abort the poll loop for the record we do want.
                }
            }
        }
        return null;
    }

    private static KafkaConsumer<String, String> newConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        // Fresh, unique group per test invocation - see class javadoc for why.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "api-tests-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return new KafkaConsumer<>(props);
    }
}
