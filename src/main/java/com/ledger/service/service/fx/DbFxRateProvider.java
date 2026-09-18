package com.ledger.service.service.fx;

import com.ledger.service.domain.FxRate;
import com.ledger.service.repository.FxRateRepository;
import com.ledger.service.service.exception.UnsupportedCurrencyPairException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link FxRateProvider}: reads static, seeded rates from the
 * {@code fx_rates} table (see {@code V7__create_fx_rates_table.sql}) -
 * explicitly NOT a live external FX API call; that remains a documented
 * scope cut for this phase. A later phase can add a live provider as a
 * separate {@code @Component} implementing {@link FxRateProvider} without
 * touching this class or the schema.
 *
 * <h2>Lookup rule</h2>
 * The most recent {@code fx_rates} row for the exact requested {@code
 * (fromCurrency, toCurrency)} pair whose {@code effective_at <= now()} -
 * i.e. "the latest rate known to be in effect right now," never a
 * future-dated rate. Never triangulated through a third currency and never
 * inverted from the opposite pair: only rows explicitly seeded for the
 * exact requested pair are considered (see the V7 migration comment for why
 * both directions of each pair are seeded explicitly instead of relying on
 * inversion).
 *
 * <h2>Rounding - the single conversion point</h2>
 * {@code amount.multiply(rate)} is rounded to scale {@value
 * #BASE_AMOUNT_SCALE} using {@link RoundingMode#HALF_UP}, matching the
 * existing {@code entries.amount NUMERIC(19,4)} column's scale (V3). This
 * is kept as one uniform, documented conversion point - here, and nowhere
 * else in the codebase - rather than scattered rounding calls, precisely so
 * the V9 balance trigger's zero-drift, no-epsilon equality check can hold
 * exactly: every {@code base_currency_amount} that is ever summed was
 * already rounded consistently at the moment it was computed.
 *
 * <h2>Same-currency identity path</h2>
 * {@code fromCurrency.equals(toCurrency)} is short-circuited BEFORE any
 * {@code fx_rates} query - it never becomes a DB lookup - even though
 * everything downstream of {@link #convert} (the {@code TransactionWriter}
 * call site, {@code Entry} construction/persistence, and the V9 balance
 * trigger) runs the exact same code path for a same-currency entry as for a
 * cross-currency one. This is deliberate, not an oversight: requiring a
 * self-referential (e.g. {@code USD -> USD}) row for every currency ever
 * used would be an unbounded seeding burden, and would turn the
 * overwhelmingly common same-currency case into a spurious {@link
 * UnsupportedCurrencyPairException} failure mode the moment such a row was
 * ever missing. The identity contract is therefore enforced unconditionally
 * in code, not left to depend on what happens to be seeded in the table.
 */
@Component
public class DbFxRateProvider implements FxRateProvider {

    private static final int BASE_AMOUNT_SCALE = 4;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    // Represented at the same scale (8) as fx_rates.rate / entries.fx_rate_used
    // purely for consistency of representation - not a rounding decision,
    // since 1 is exact at any scale.
    private static final BigDecimal IDENTITY_RATE = BigDecimal.ONE.setScale(8, RoundingMode.UNNECESSARY);

    private final FxRateRepository fxRateRepository;

    public DbFxRateProvider(FxRateRepository fxRateRepository) {
        this.fxRateRepository = fxRateRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public FxConversionResult convert(String fromCurrency, String toCurrency, BigDecimal amount) {
        Objects.requireNonNull(fromCurrency, "fromCurrency must not be null");
        Objects.requireNonNull(toCurrency, "toCurrency must not be null");
        Objects.requireNonNull(amount, "amount must not be null");

        if (fromCurrency.equals(toCurrency)) {
            return new FxConversionResult(amount, IDENTITY_RATE, Instant.now());
        }

        FxRate rate = fxRateRepository
                .findFirstByFromCurrencyAndToCurrencyAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(
                        fromCurrency, toCurrency, Instant.now())
                .orElseThrow(() -> new UnsupportedCurrencyPairException(fromCurrency, toCurrency));

        BigDecimal convertedAmount = amount.multiply(rate.getRate())
                .setScale(BASE_AMOUNT_SCALE, ROUNDING_MODE);

        return new FxConversionResult(convertedAmount, rate.getRate(), rate.getEffectiveAt());
    }
}
