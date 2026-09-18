package com.ledger.service.service.fx;

import com.ledger.service.service.exception.UnsupportedCurrencyPairException;
import java.math.BigDecimal;

/**
 * Converts an amount from one ISO 4217 currency to another.
 *
 * <p>Deliberately pluggable: {@link DbFxRateProvider} (reading static,
 * seeded rates from the {@code fx_rates} table - see {@code
 * V7__create_fx_rates_table.sql}) is the only implementation wired up in
 * this phase, but {@code TransactionWriter} - the only caller - depends
 * only on this interface. Swapping in a live external FX API later is a
 * single new {@code @Component} implementing this interface (e.g. marked
 * {@code @Primary}, or profile-scoped), with no change to the
 * posting/persistence code path and no schema change required.
 *
 * <h2>Same-currency contract</h2>
 * {@code fromCurrency.equals(toCurrency)} MUST be a well-defined identity
 * conversion: {@code convertedAmount} equal in value to {@code amount}, and
 * {@code rateUsed} representing exactly 1. Every implementation must
 * guarantee this unconditionally, in code - never merely as a side effect
 * of the underlying rate source happening to contain a self-referential
 * 1:1 rate row - because callers, and downstream invariants such as the DB
 * balance trigger, depend on it always succeeding for a same-currency
 * posting regardless of what the rate source contains.
 *
 * @throws UnsupportedCurrencyPairException if no applicable rate can be
 *         found for a genuinely cross-currency pair
 */
public interface FxRateProvider {

    FxConversionResult convert(String fromCurrency, String toCurrency, BigDecimal amount);
}
