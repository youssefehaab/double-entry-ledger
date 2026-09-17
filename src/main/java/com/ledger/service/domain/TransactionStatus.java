package com.ledger.service.domain;

/**
 * Mirrors the {@code chk_transactions_status} CHECK constraint in
 * {@code V2__create_transactions_table.sql}.
 */
public enum TransactionStatus {
    PENDING,
    POSTED,
    FAILED
}
