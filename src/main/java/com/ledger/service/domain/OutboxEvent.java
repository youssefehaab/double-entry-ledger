package com.ledger.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row per domain event that must be delivered to Kafka, written in the
 * SAME DB transaction as the state change it describes (see
 * {@code TransactionWriter#createAndPersist}) - the transactional outbox
 * pattern. A separate process ({@code OutboxRelay}) is solely responsible
 * for reading PENDING rows and publishing them; nothing on the write path
 * ever talks to Kafka directly, so "the transaction posted" and "an event
 * describing it was durably recorded" either both happen or neither does.
 *
 * <h2>{@code payload} JSONB mapping - judgment call, flagged for review</h2>
 * {@code payload} is a plain {@link String} field, already-serialized JSON
 * text (see {@code OutboxEventFactory}, which does the actual Jackson
 * {@code ObjectMapper.writeValueAsString(...)} call before constructing this
 * entity) mapped to the {@code jsonb} column via
 * {@link JdbcTypeCode}({@link SqlTypes#JSON}) + {@code columnDefinition =
 * "jsonb"}. Hibernate 6's {@code JsonJdbcType} binds/reads a {@code String}-
 * typed JSON attribute as raw JSON text directly (no re-serialization
 * through its own format mapper, which only kicks in for non-String Java
 * types) - so this gets a correctly-bound {@code jsonb} column (a plain
 * {@code String} + {@code columnDefinition} alone is not sufficient with the
 * Postgres JDBC driver, which needs the value bound as a {@code PGobject}
 * with type {@code jsonb}, not a plain varchar parameter) while keeping
 * serialization fully explicit and under this codebase's control - what
 * exactly is serialized, and how, lives in one place
 * ({@code OutboxEventFactory}) instead of being inferred by Hibernate's
 * reflection-based JSON (de)serialization of a record/POJO-typed attribute.
 * The alternative (mapping this field's type directly to the payload record
 * and letting Hibernate serialize/deserialize it) was rejected because it
 * would make the exact wire shape of the event implicit in JPA mapping
 * rather than an explicit, testable serialization step.
 *
 * <h2>{@code jsonb} numeric canonicalization - caveat for consumers</h2>
 * Postgres's {@code jsonb} type (unlike {@code json}) does not preserve the
 * original textual formatting of numbers: it stores them via its internal
 * {@code numeric} type and can reformat trailing zeros on read-back (e.g. a
 * serialized {@code "108.0000"} may read back from the column as
 * {@code "108.0"} - the numeric VALUE is unchanged, only its textual scale/
 * trailing-zero formatting is not guaranteed to round-trip exactly). Any
 * consumer of this payload (including the relay's own read path, and
 * {@code TransactionPostedEventConsumer}/any real future consumer) must
 * compare/parse these fields as arbitrary-precision decimals by value, never
 * assume a fixed number of decimal places. See
 * {@code TransactionWriterOutboxAtomicityIntegrationTest} for where this was
 * actually observed and is asserted against with value-based (not
 * string-exact) comparisons.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    protected OutboxEvent() {
        // required by JPA
    }

    public OutboxEvent(String aggregateType, UUID aggregateId, String eventType, String payload) {
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    /**
     * PENDING -&gt; PUBLISHED, called by {@code OutboxRelay} after a Kafka
     * send has been acknowledged (see that class for the sync-vs-async
     * tradeoff). Deliberately does not increment {@code attemptCount}: that
     * field counts FAILED attempts only, so a row published on its very
     * first try has {@code attemptCount == 0}, matching the
     * {@code attempt_count INTEGER default 0} column's intent.
     *
     * @throws IllegalStateException if this row is not currently PENDING
     */
    public void markPublished(Instant publishedAt) {
        if (this.status != OutboxStatus.PENDING) {
            throw new IllegalStateException("cannot mark outbox event as PUBLISHED from status " + this.status);
        }
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    }

    /**
     * Records a failed publish attempt: increments {@code attemptCount} and
     * stamps {@code lastAttemptAt} (the backoff clock {@code OutboxRelay}'s
     * eligibility query reads from). If this attempt exhausts {@code
     * maxAttempts}, transitions PENDING -&gt; FAILED (dead-letter, never
     * retried again); otherwise stays PENDING for a later, backed-off retry.
     *
     * @throws IllegalStateException if this row is not currently PENDING
     */
    public void recordFailedAttempt(Instant attemptedAt, int maxAttempts) {
        if (this.status != OutboxStatus.PENDING) {
            throw new IllegalStateException("cannot record a failed attempt on outbox event with status " + this.status);
        }
        this.attemptCount++;
        this.lastAttemptAt = Objects.requireNonNull(attemptedAt, "attemptedAt must not be null");
        if (this.attemptCount >= maxAttempts) {
            this.status = OutboxStatus.FAILED;
        }
    }
}
