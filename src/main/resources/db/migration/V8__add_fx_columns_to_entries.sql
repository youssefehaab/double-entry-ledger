-- Adds the base-currency (reporting-currency) columns to entries so that,
-- from Phase 1 of v1 -> v1.1 onward, every entry carries both its native
-- amount (unchanged - amount/currency-via-account remain the audit/native
-- values, per V3) AND a durable, point-in-time snapshot of what that
-- amount converted to in the ledger's base currency, plus exactly the rate
-- used to get there.
--
-- Column scale/precision judgment calls (flagged for backend-reviewer):
--
--   base_currency_amount NUMERIC(19,4) - matches entries.amount's existing
--   scale exactly (V3). This is a deliberate consistency choice, not a
--   currency-aware one: real-world practice would use 0 fraction digits for
--   JPY and 2 for most other fiat currencies, but entries.amount already
--   made the simplifying choice to use one fixed scale (4) uniformly
--   regardless of the entry's actual currency (see V3/Entry.java) - this
--   migration keeps that same precedent for base_currency_amount rather
--   than introducing a second, inconsistent precision rule. 4 fraction
--   digits is generous enough to hold a 2-decimal fiat amount converted
--   through an 8-decimal rate without losing money to rounding beyond what
--   the existing amount column already tolerates.
--
--   fx_rate_used NUMERIC(19,8) - matches fx_rates.rate's scale (V7), so the
--   value copied onto the entry is bit-for-bit the same precision as its
--   source, with no extra rounding applied to the rate itself. The only
--   rounding in the whole pipeline happens once, when amount * rate is
--   reduced to base_currency_amount's scale-4 - see DbFxRateProvider.
--
-- Per-entry (not per-transaction) FX rate granularity (flagged for
-- backend-reviewer): a transaction's individual legs can reference accounts
-- in *different* currencies (e.g. one leg on a EUR account, another on a
-- USD account, in the same FX-trade transaction), so "the" FX rate for a
-- transaction is not even well-defined in general - only "the FX rate for
-- this entry's account currency -> base currency" is. Recording per-entry
-- is therefore not just finer-grained but strictly more correct: it is
-- never wrong, whereas a single per-transaction rate would be ambiguous or
-- outright wrong for any multi-currency transaction. Cost: a same-currency
-- transaction repeats an identical (identity) rate on every one of its
-- entries rather than storing it once - an acceptable, deliberate trade for
-- correctness and simplicity over a few bytes of duplication.
--
-- Nullability (flagged for backend-reviewer): all three new columns are
-- added NULLable, deliberately NOT NOT NULL at the DB level, even though
-- the application (Entry.java's constructor) always supplies all three for
-- every entry it creates from this point forward. A blanket NOT NULL here
-- would either fail this migration outright against a live database that
-- already has entries rows (pre-FX, correctly missing this data - there is
-- no historically-accurate FX rate to backfill them with, and V4 forbids
-- ever UPDATing them to add one after the fact - fabricating one would be
-- an audit falsification, not a backfill), or require a separate backfill
-- migration with a policy this codebase has no basis to choose. Leaving the
-- columns NULLable at the DB level and enforcing "always set together, or
-- not at all" via the CHECK constraint below is the safe, honest migration:
-- old rows stay exactly as they were recorded, and new rows are structurally
-- guaranteed to be internally consistent. See V9 for how the balance
-- trigger additionally makes "no base_currency_amount" a hard failure for
-- any *new* write, closing the gap this nullability otherwise leaves open.
--
-- All three ADD COLUMN statements below are metadata-only (no default, no
-- rewrite) and safe on a large, live entries table - Postgres does not
-- rewrite or lock-scan the table to add a nullable column with no default.
ALTER TABLE entries ADD COLUMN base_currency_amount NUMERIC(19, 4);
ALTER TABLE entries ADD COLUMN fx_rate_used NUMERIC(19, 8);
ALTER TABLE entries ADD COLUMN fx_rate_effective_at TIMESTAMPTZ;

-- CHECK constraints, by contrast, DO require validating every existing row,
-- which for a CHECK added the normal way takes an ACCESS EXCLUSIVE lock for
-- the duration of a full table scan. Added NOT VALID (cheap, brief lock,
-- applies only to future writes immediately) then validated in a separate
-- statement (VALIDATE CONSTRAINT only needs a SHARE UPDATE EXCLUSIVE lock,
-- which does not block concurrent reads/writes) - the same
-- lock-duration-conscious pattern already used for the composite index in
-- V6, applied here to constraints instead of an index build.
ALTER TABLE entries
    ADD CONSTRAINT chk_entries_base_currency_amount_positive
        CHECK (base_currency_amount IS NULL OR base_currency_amount > 0) NOT VALID;
ALTER TABLE entries VALIDATE CONSTRAINT chk_entries_base_currency_amount_positive;

ALTER TABLE entries
    ADD CONSTRAINT chk_entries_fx_columns_together
        CHECK (
            (base_currency_amount IS NULL AND fx_rate_used IS NULL AND fx_rate_effective_at IS NULL)
            OR
            (base_currency_amount IS NOT NULL AND fx_rate_used IS NOT NULL AND fx_rate_effective_at IS NOT NULL)
        ) NOT VALID;
ALTER TABLE entries VALIDATE CONSTRAINT chk_entries_fx_columns_together;
