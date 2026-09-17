-- Accounts: the chart-of-accounts entries that entries post against.
--
-- Judgment call: UUID primary keys (via built-in gen_random_uuid(), PostgreSQL
-- 13+ core function, no extension required) instead of bigserial. Chosen so
-- account/transaction/entry identifiers are safe to hand back from a public
-- API without leaking creation order/volume, and so IDs can be generated
-- client-side later (e.g. for idempotent retries) without a round trip.
-- Trade-off: larger index footprint and no natural sort-by-creation-order
-- versus bigserial - acceptable for a ledger where created_at already gives
-- chronological ordering. Flag for reviewer: revisit if insert throughput on
-- a single hot index ever becomes a bottleneck (UUIDv4 is not sequential and
-- causes more index page splits than bigserial or UUIDv7).
--
-- Judgment call: account_type is a VARCHAR + CHECK constraint rather than a
-- native Postgres ENUM type. Adding a new value to a CHECK constraint is a
-- plain ALTER TABLE ... DROP/ADD CONSTRAINT in a Flyway migration; adding a
-- value to a native enum type (ALTER TYPE ... ADD VALUE) cannot run inside
-- the same transaction as other DDL/DML using it on older PostgreSQL, which
-- is awkward for forward-only Flyway migrations. CHECK also maps directly to
-- a Java enum with @Enumerated(EnumType.STRING) with no extra Hibernate
-- UserType/converter plumbing.
CREATE TABLE accounts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(255)     NOT NULL,
    -- ISO 4217 currency code. Storage only - no FX/conversion logic in this
    -- service (explicitly out of scope).
    currency     VARCHAR(3)       NOT NULL,
    account_type VARCHAR(20)      NOT NULL,
    created_at   TIMESTAMPTZ      NOT NULL DEFAULT now(),

    CONSTRAINT chk_accounts_account_type
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY')),
    CONSTRAINT chk_accounts_currency_format
        CHECK (currency ~ '^[A-Z]{3}$')
);

COMMENT ON TABLE accounts IS
    'Chart-of-accounts entries. No stored balance column: balances are always '
    'derived from entries (see entries table), never cached/mutated here.';
