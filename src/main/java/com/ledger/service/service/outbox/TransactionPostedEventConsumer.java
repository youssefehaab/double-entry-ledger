package com.ledger.service.service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Minimal logging consumer for {@code transaction-posted-events}.
 *
 * <p><b>This is explicitly NOT a real downstream service</b> - there is no
 * business logic here, no side effect other than a log line, and no
 * persistence of its own. Its only purpose is to prove the pipeline works
 * end-to-end (TransactionWriter -&gt; outbox row -&gt; OutboxRelay -&gt;
 * real Kafka broker -&gt; a real consumer receives it) and to give the
 * separate tester agent something concrete - a real Kafka consumer
 * observing a real message - to assert against in integration tests,
 * rather than only asserting against {@code outbox_events.status} in
 * Postgres. A real downstream consumer (e.g. a reporting/analytics service)
 * would replace or sit alongside this one, on its own consumer group, doing
 * real work and its own idempotent handling.
 *
 * <p>Uses Spring Kafka's default consumer deserialization (string key/value -
 * see {@code spring.kafka.consumer.*} in application.yml) and leaves
 * payload deserialization to whatever a real consumer would need; here the
 * raw JSON is only inspected enough to log the fields called out in this
 * phase's scope (transaction id, event type), via a throwaway parse rather
 * than depending on {@link TransactionPostedEventPayload} JSON evolving in
 * lockstep with this consumer.
 */
@Component
public class TransactionPostedEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionPostedEventConsumer.class);

    private final ObjectMapper objectMapper;

    public TransactionPostedEventConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${ledger.outbox.topic:transaction-posted-events}",
            groupId = "${spring.kafka.consumer.group-id:ledger-outbox-proof-of-pipeline}")
    public void onMessage(String payload) {
        String transactionId = "unknown";
        try {
            var node = objectMapper.readTree(payload);
            if (node.has("transactionId")) {
                transactionId = node.get("transactionId").asText();
            }
        } catch (Exception e) {
            log.warn("Received transaction-posted-events message that could not be parsed as JSON", e);
        }
        log.info("Received TransactionPosted event for transaction {}", transactionId);
    }
}
