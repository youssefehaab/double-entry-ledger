package com.ledger.service.integration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests that additionally need a real Kafka
 * broker (outbox relay / consumer tests) - Postgres-only tests should extend
 * {@link AbstractIntegrationTest} directly instead.
 *
 * <p>Same image ({@code apache/kafka:3.8.0}) and mode (native KRaft, via
 * {@code org.testcontainers.kafka.KafkaContainer} - the module added
 * specifically for the upstream Apache Kafka images, as opposed to {@code
 * org.testcontainers.kafka.ConfluentKafkaContainer}) as the real broker
 * platform-engineer provisioned in {@code docker-compose.yml}, so what these
 * tests exercise matches what actually runs in the real stack.
 *
 * <p>Same "singleton container, started once in a static initializer, never
 * stopped by test code" pattern as {@link AbstractIntegrationTest} uses for
 * Postgres, for the same reason: every subclass of this base class shares
 * one cached Spring ApplicationContext (keyed by this class's {@code
 * @DynamicPropertySource} methods), so the container must outlive every
 * individual test class that uses it.
 *
 * <p>Unlike the real docker-compose stack, nothing here runs the {@code
 * kafka-init} one-shot topic-provisioning container, so {@link
 * #ensureOutboxTopicExists()} explicitly creates {@code
 * transaction-posted-events} once before any test in this hierarchy runs,
 * rather than relying on broker auto-create-topic timing (which - even when
 * enabled - can race a producer's first send against topic creation).
 */
public abstract class AbstractKafkaIntegrationTest extends AbstractIntegrationTest {

    protected static final String OUTBOX_TOPIC = "transaction-posted-events";

    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void ensureOutboxTopicExists() throws ExecutionException, InterruptedException {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            try {
                admin.createTopics(java.util.List.of(new NewTopic(OUTBOX_TOPIC, 3, (short) 1)))
                        .all()
                        .get();
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof TopicExistsException)) {
                    throw e;
                }
            }
            // Give the broker a brief moment to finish propagating topic
            // metadata before the first test tries to produce/consume -
            // avoids a rare first-send race immediately after creation.
            Thread.sleep(Duration.ofMillis(200));
        }
    }
}
