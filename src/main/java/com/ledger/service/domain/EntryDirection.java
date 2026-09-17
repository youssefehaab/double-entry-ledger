package com.ledger.service.domain;

/**
 * Mirrors the {@code chk_entries_direction} CHECK constraint in
 * {@code V3__create_entries_table.sql}. Amounts are stored unsigned
 * ({@code amount > 0}); direction is what makes an entry a debit or a
 * credit, so the DB-level balance trigger can do a simple
 * sum(DEBIT) == sum(CREDIT) per transaction.
 */
public enum EntryDirection {
    DEBIT,
    CREDIT
}
