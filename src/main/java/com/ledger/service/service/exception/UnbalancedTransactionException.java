package com.ledger.service.service.exception;

import java.math.BigDecimal;

/**
 * Thrown by the app-level pre-check when a requested set of entries does
 * not balance (sum(DEBIT) != sum(CREDIT)). Maps to HTTP 422 Unprocessable
 * Entity.
 *
 * <p>This is a fast, clean, pure in-memory check performed before any DB
 * write is attempted. It is deliberately not the only line of defense: the
 * DB-level deferred constraint trigger from Phase 1
 * ({@code V5__enforce_transaction_balance.sql}) still enforces the same
 * invariant unconditionally at COMMIT, so even a bug in this app-level
 * check (or a future code path that bypasses it) can never result in an
 * unbalanced transaction being persisted.
 */
public class UnbalancedTransactionException extends RuntimeException {

    public UnbalancedTransactionException(BigDecimal debitTotal, BigDecimal creditTotal) {
        super("transaction entries are not balanced: debit total " + debitTotal
                + " != credit total " + creditTotal);
    }
}
