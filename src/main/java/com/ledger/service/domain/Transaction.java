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

/**
 * The atomic unit that groups a balanced set of {@link Entry} rows.
 *
 * <p>Table is named {@code transactions} (see the V2 migration) to avoid
 * clashing with the TRANSACTION keyword used by SQL tooling; the Java class
 * keeps the singular, more natural domain name.
 *
 * <p>Phase 2 adds the one transition this service currently drives:
 * PENDING -&gt; POSTED, via {@link #markPosted(Instant)}, called once, in
 * memory, before the transaction (and its entries) are ever persisted - so
 * only POSTED transactions are ever written to the DB in this phase. There
 * is no asynchronous/PENDING-then-later-POSTED flow yet (that would be a
 * future phase), so FAILED is likewise not driven by any code path yet;
 * both remain valid per the {@code chk_transactions_status} CHECK
 * constraint for forward compatibility.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "description", nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status = TransactionStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "posted_at")
    private Instant postedAt;

    protected Transaction() {
        // required by JPA
    }

    public Transaction(String idempotencyKey, String description) {
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.status = TransactionStatus.PENDING;
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getDescription() {
        return description;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    /**
     * Transitions this transaction from PENDING to POSTED and stamps
     * {@code postedAt}. Deliberately the only state-mutating method on this
     * class (entries remain fully immutable, per {@link Entry}) and
     * deliberately not a generic setter: the only legal transition modeled
     * today is PENDING -&gt; POSTED, so that is the only thing this API
     * allows.
     *
     * @throws IllegalStateException if called on a transaction that is not
     *         currently PENDING (guards against double-posting the same
     *         in-memory instance)
     */
    public void markPosted(Instant postedAt) {
        if (this.status != TransactionStatus.PENDING) {
            throw new IllegalStateException(
                    "cannot mark transaction as POSTED from status " + this.status);
        }
        this.status = TransactionStatus.POSTED;
        this.postedAt = Objects.requireNonNull(postedAt, "postedAt must not be null");
    }
}
