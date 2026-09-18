package com.ledger.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A single FX rate quote for converting {@code fromCurrency} into {@code
 * toCurrency}, effective as of {@code effectiveAt}.
 *
 * <p>Reference/lookup data only - the application never writes rows here
 * (rows are seeded exclusively by {@code V7__create_fx_rates_table.sql});
 * no other table has a foreign key to this one. Once an entry is posted,
 * the rate it actually used is copied verbatim onto the entry itself
 * ({@code fx_rate_used} / {@code fx_rate_effective_at} - see {@link
 * Entry}), precisely so that a later correction or addition to this table
 * can never retroactively change a historical entry's recorded
 * base-currency amount.
 *
 * <p>This table backs {@link com.ledger.service.service.fx.DbFxRateProvider},
 * the default implementation of {@link com.ledger.service.service.fx.FxRateProvider}.
 * Swapping in a live external FX API later means adding a new {@code
 * FxRateProvider} Spring bean, not touching this entity or its table.
 */
@Entity
@Table(name = "fx_rates")
public class FxRate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "from_currency", nullable = false, updatable = false, length = 3)
    private String fromCurrency;

    @Column(name = "to_currency", nullable = false, updatable = false, length = 3)
    private String toCurrency;

    @Column(name = "rate", nullable = false, updatable = false, precision = 19, scale = 8)
    private BigDecimal rate;

    @Column(name = "effective_at", nullable = false, updatable = false)
    private Instant effectiveAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FxRate() {
        // required by JPA
    }

    public UUID getId() {
        return id;
    }

    public String getFromCurrency() {
        return fromCurrency;
    }

    public String getToCurrency() {
        return toCurrency;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
