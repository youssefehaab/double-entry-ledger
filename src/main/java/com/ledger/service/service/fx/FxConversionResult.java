package com.ledger.service.service.fx;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The result of converting an amount from one currency to another via
 * {@link FxRateProvider#convert}.
 *
 * @param convertedAmount the source amount converted into the target
 *                         currency, rounded to the single fixed scale this
 *                         service persists base-currency amounts at - see
 *                         {@link DbFxRateProvider}'s class-level javadoc for
 *                         the one point where that rounding happens
 * @param rateUsed the exact rate applied, persisted verbatim on the
 *                  resulting {@code entries} row so that a later change to
 *                  the underlying rate source can never retroactively
 *                  change what a historical entry recorded
 * @param rateEffectiveAt when {@code rateUsed} was/is effective - for a
 *                         same-currency (identity) conversion this is the
 *                         instant of the conversion call itself, since
 *                         there is no underlying rate row to date
 */
public record FxConversionResult(BigDecimal convertedAmount, BigDecimal rateUsed, Instant rateEffectiveAt) {
}
