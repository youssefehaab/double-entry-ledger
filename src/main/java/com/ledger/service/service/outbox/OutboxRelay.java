package com.ledger.service.service.outbox;

import com.ledger.service.domain.OutboxEvent;
import com.ledger.service.domain.OutboxStatus;
import com.ledger.service.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Background relay that drains {@code outbox_events} rows into Kafka.
 *
 * <h2>Transaction boundary - one row per DB transaction, flagged for review</h2>
 * {@link #relayNextEligibleEvent()} (not {@link #poll()}) is the {@code
 * @Transactional} method, and it locks, publishes, and updates the status of
 * AT MOST ONE row per call/transaction - see
 * {@link OutboxEventRepository#lockNextEligibleForRelay}. {@link #poll()}
 * itself is not transactional; it just loops, calling that method up to
 * {@code ledger.outbox.batch-size} times per scheduled tick. This was a
 * deliberate choice over locking a whole batch of rows in one transaction:
 * with a shared batch transaction, if row 5 of 20 fails to commit for any
 * reason after rows 1-4 already had successful Kafka sends, the whole
 * transaction (including rows 1-4's now-true PUBLISHED status) rolls back,
 * and the next poll re-publishes rows 1-4 to Kafka a second time. Per-row
 * transactions shrink that "already sent to Kafka but the DB commit that
 * would have recorded it failed" window to a single row instead of the
 * whole batch. Some duplicate delivery is still possible in that narrow
 * window (this is standard at-least-once outbox-relay behavior, not a bug
 * unique to this implementation) - {@code TransactionPostedEventConsumer}
 * (and any real downstream consumer) must be idempotent, e.g. by
 * deduplicating on {@code transactionId}, which is stable and unique per
 * event.
 *
 * <h2>Sync vs. async Kafka send - flagged for review</h2>
 * {@link #relayNextEligibleEvent()} calls {@code kafkaTemplate.send(...).get(...)}
 * - a SYNCHRONOUS, blocking send - rather than attaching an async callback.
 * Chosen because this method's contract is "the row is marked PUBLISHED iff
 * the send it corresponds to is known, at the point that update happens, to
 * have been acknowledged by Kafka" (leaning on {@code acks=all} +
 * idempotent producer, already configured in application.yml, for the
 * broker-side durability guarantee that ack represents) - a blocking wait
 * makes that ordering trivial to get right and trivial to prove correct. An
 * async callback would decouple "send issued" from "row updated," which
 * either means doing the DB update from inside the callback (on a Kafka
 * producer I/O thread, reaching back into a JPA/Hibernate session that
 * belongs to a different thread - not safe) or re-polling for outcomes,
 * meaningfully complicating this class for a throughput gain this relay
 * does not need yet: it processes at most one in-flight send per poller
 * thread at a time regardless, so the ceiling this synchronous design
 * imposes is roughly {@code batch-size / (kafka-send-timeout-ms worst
 * case)} events per poll interval - acceptable at this project's scale.
 * Revisit (batched/pipelined async sends with a dedicated completion
 * handler) if relay throughput ever becomes the bottleneck.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final int batchSize;
    private final int maxAttempts;
    private final long backoffBaseSeconds;
    private final long backoffMaxSeconds;
    private final long sendTimeoutMs;

    public OutboxRelay(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${ledger.outbox.topic:transaction-posted-events}") String topic,
            @Value("${ledger.outbox.batch-size:20}") int batchSize,
            @Value("${ledger.outbox.max-attempts:5}") int maxAttempts,
            @Value("${ledger.outbox.backoff-base-seconds:30}") long backoffBaseSeconds,
            @Value("${ledger.outbox.backoff-max-seconds:900}") long backoffMaxSeconds,
            @Value("${ledger.outbox.kafka-send-timeout-ms:5000}") long sendTimeoutMs) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.backoffBaseSeconds = backoffBaseSeconds;
        this.backoffMaxSeconds = backoffMaxSeconds;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    /**
     * Scheduled entry point, every {@code ledger.outbox.poll-interval-ms}
     * (default 2000ms). Drains up to {@code batchSize} eligible rows per
     * tick, stopping early the moment a tick finds nothing left to do
     * (rather than always doing exactly {@code batchSize} DB round trips)
     * so an idle system does not do pointless work every tick.
     */
    @Scheduled(fixedDelayString = "${ledger.outbox.poll-interval-ms:2000}")
    public void poll() {
        for (int i = 0; i < batchSize; i++) {
            if (!relayNextEligibleEvent()) {
                return;
            }
        }
    }

    /**
     * Locks (see {@link OutboxEventRepository#lockNextEligibleForRelay})
     * and processes exactly one eligible row, in one DB transaction. Returns
     * {@code false} (and does nothing else) if no row is currently
     * eligible - the caller uses that to stop early rather than spinning
     * through the rest of the batch for nothing.
     */
    @Transactional
    public boolean relayNextEligibleEvent() {
        Optional<OutboxEvent> maybeEvent =
                outboxEventRepository.lockNextEligibleForRelay(backoffBaseSeconds, backoffMaxSeconds);
        if (maybeEvent.isEmpty()) {
            return false;
        }

        OutboxEvent event = maybeEvent.get();
        try {
            // Keyed by aggregate_id so every event for the same aggregate
            // (only ever one TransactionPosted event per transaction today,
            // but this keying is what would keep them ordered on the same
            // partition if a future event type added more events per
            // transaction) lands on the same Kafka partition.
            kafkaTemplate.send(topic, event.getAggregateId().toString(), event.getPayload())
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            event.markPublished(Instant.now());
            // Explicit save (not left to implicit end-of-transaction dirty
            // checking): correct either way when this method runs through
            // Spring's @Transactional proxy (the only way it runs in
            // production, via the @Scheduled poll() -&gt; Spring-managed
            // bean path), but an explicit save makes persistence happen
            // regardless of whether a JPA persistence context happens to
            // span this whole method - it does not silently depend on the
            // caller going through the proxy.
            outboxEventRepository.save(event);
            log.info(
                    "Published outbox event {} (type={}, aggregateId={}) to topic {}",
                    event.getId(), event.getEventType(), event.getAggregateId(), topic);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(event, e);
        } catch (TimeoutException e) {
            recordFailure(event, e);
        } catch (Exception e) {
            // Covers ExecutionException (the send itself failed/was
            // rejected by the broker or producer) and any other unexpected
            // failure - all treated as "this attempt failed," never
            // rethrown: an uncaught exception here would abort this row's
            // transaction (fine on its own) but, thrown from inside the
            // @Scheduled poll() loop's iteration, would also stop that
            // tick's loop from moving on to the next row.
            recordFailure(event, e);
        }
        return true;
    }

    private void recordFailure(OutboxEvent event, Exception cause) {
        event.recordFailedAttempt(Instant.now(), maxAttempts);
        // See the comment on the success path's save() call above - same
        // reasoning applies here.
        outboxEventRepository.save(event);
        if (event.getStatus() == OutboxStatus.FAILED) {
            log.error(
                    "Outbox event {} (type={}, aggregateId={}) exhausted {} attempts and is now FAILED (dead-lettered)",
                    event.getId(), event.getEventType(), event.getAggregateId(), maxAttempts, cause);
        } else {
            log.warn(
                    "Failed to publish outbox event {} (type={}, aggregateId={}), attempt {}/{}; will retry after backoff",
                    event.getId(), event.getEventType(), event.getAggregateId(), event.getAttemptCount(), maxAttempts,
                    cause);
        }
    }
}
