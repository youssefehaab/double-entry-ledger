package com.ledger.service.repository;

import com.ledger.service.domain.FxRate;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FxRateRepository extends JpaRepository<FxRate, UUID> {

    /**
     * Lookup rule used by {@link com.ledger.service.service.fx.DbFxRateProvider}:
     * the most recent rate for this exact currency pair whose {@code
     * effective_at} is at or before {@code asOf} - "the latest rate known to
     * be in effect at this instant," never a future-dated one, and never an
     * inversion/triangulation of a rate seeded only for the opposite pair or
     * via a third currency. Empty when no such row exists (unsupported
     * pair, or every seeded row for this pair is still in the future) - see
     * {@link com.ledger.service.service.exception.UnsupportedCurrencyPairException}.
     */
    Optional<FxRate> findFirstByFromCurrencyAndToCurrencyAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(
            String fromCurrency, String toCurrency, Instant asOf);
}
