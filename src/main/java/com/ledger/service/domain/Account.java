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
 * A chart-of-accounts entry.
 *
 * <p>Deliberately has <b>no</b> stored balance field. Balances must always
 * be derived by summing {@link Entry} rows for this account (starting
 * Phase 2) - never cached/mutated on this entity, which would make it
 * possible for a stored balance to drift out of sync with the append-only
 * entries that are the actual source of truth.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
        // required by JPA
    }

    public Account(String name, String currency, AccountType accountType) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.accountType = Objects.requireNonNull(accountType, "accountType must not be null");
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
