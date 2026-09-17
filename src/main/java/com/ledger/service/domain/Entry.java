package com.ledger.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A single debit or credit posting against an {@link Account}, as part of a
 * {@link Transaction}.
 *
 * <p>Deliberately exposes no setters and no mutators of any kind: entries
 * are append-only. This mirrors the DB-level guarantee in
 * {@code V4__enforce_entries_immutability.sql}, where a trigger rejects any
 * UPDATE/DELETE on this table outright - the Java model refuses to even
 * offer a way to attempt a mutation.
 *
 * <p>{@code amount} is a {@link BigDecimal}, never a float/double, matching
 * the NUMERIC(19,4) column - see V3 migration.
 */
@Entity
@Table(name = "entries")
public class Entry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false, length = 10)
    private EntryDirection direction;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Entry() {
        // required by JPA
    }

    public Entry(Transaction transaction, Account account, BigDecimal amount, EntryDirection direction) {
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        this.account = Objects.requireNonNull(account, "account must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive, got: " + amount);
        }
    }

    public UUID getId() {
        return id;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public Account getAccount() {
        return account;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
