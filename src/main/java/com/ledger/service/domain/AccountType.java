package com.ledger.service.domain;

/**
 * Mirrors the {@code chk_accounts_account_type} CHECK constraint in
 * {@code V1__create_accounts_table.sql}. Kept as a plain Java enum mapped
 * via {@code @Enumerated(EnumType.STRING)} rather than a native Postgres
 * enum type - see the migration comment for why.
 */
public enum AccountType {
    ASSET,
    LIABILITY,
    EQUITY
}
