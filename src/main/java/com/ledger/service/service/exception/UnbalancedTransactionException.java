package com.ledger.service.service.exception;

import java.math.BigDecimal;

/**
 * Thrown by the app-level pre-check ({@code TransactionBalanceValidator})
 * when a set of entries does not balance in the ledger's base currency
 * (sum(DEBIT base_currency_amount) != sum(CREDIT base_currency_amount)).
 * Maps to HTTP 422 Unprocessable Entity.
 *
 * <p>A fast, clean, pure in-memory check - no DB read/write of its own -
 * performed inside {@code TransactionWriter#createAndPersist}, strictly
 * before any {@code Entry} row is written (though, as of multi-currency
 * support, after accounts are loaded/locked and FX-converted, since a
 * currency-aware check needs that information first - see {@code
 * TransactionWriter}'s and {@code TransactionBalanceValidator}'s javadoc).
 * It is deliberately not the only line of defense: the DB-level deferred
 * constraint trigger ({@code V9__enforce_transaction_balance_base_currency.sql},
 * superseding the original Phase 1 {@code V5__enforce_transaction_balance.sql})
 * still enforces the same invariant unconditionally at COMMIT, so even a
 * bug in this app-level check (or a future code path that bypasses it) can
 * never result in an unbalanced transaction being persisted.
 */
public class UnbalancedTransactionException extends RuntimeException {

    public UnbalancedTransactionException(BigDecimal debitTotal, BigDecimal creditTotal) {
        super("transaction entries are not balanced: debit total " + debitTotal
                + " != credit total " + creditTotal);
    }
}
