package com.ledger.service.service.exception;

/**
 * Thrown when {@link com.ledger.service.service.fx.FxRateProvider#convert}
 * cannot find an applicable FX rate for a requested currency pair (no
 * matching {@code fx_rates} row for the exact pair, or every seeded row for
 * it is future-dated relative to the lookup instant). Maps to HTTP 422
 * Unprocessable Entity, following the same convention as {@link
 * UnbalancedTransactionException}: the request is well-formed and every
 * referenced account exists, but it cannot be posted as requested because
 * the service cannot express one of its legs in the configured base
 * currency.
 */
public class UnsupportedCurrencyPairException extends RuntimeException {

    public UnsupportedCurrencyPairException(String fromCurrency, String toCurrency) {
        super("no FX rate available for currency pair " + fromCurrency + " -> " + toCurrency);
    }
}
