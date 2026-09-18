-- Reference/lookup table for FX conversion (Phase 1 of v1 -> v1.1: multi-
-- currency posting). This is explicitly NOT a live external FX API call -
-- that is a documented scope cut for this phase. The table is designed so
-- a live provider can be added later as a single new Spring bean
-- implementing com.ledger.service.service.fx.FxRateProvider, with zero
-- schema change: nothing else in the schema references this table by
-- foreign key (see the fx_rate_used/fx_rate_effective_at columns added to
-- entries in V8, which copy the rate actually used at insert time onto the
-- entry itself, precisely so this table can be corrected/extended later
-- without ever retroactively changing a historical entry).
--
-- Representation: one row per (from_currency, to_currency, effective_at)
-- quote, rather than a single mutable "current rate" row per pair. This
-- keeps the table itself append-only/historical (new rates are added as
-- new rows, never UPDATEd in place) and lets the lookup rule (see below,
-- and DbFxRateProvider) pick the correct rate as of any point in time.
--
-- rate NUMERIC(19,8): 8 fraction digits is a common FX-industry convention
-- (enough precision that compounding rounding error from the rate itself is
-- negligible versus the fixed-scale-4 base_currency_amount it ultimately
-- produces - see the V8 migration and DbFxRateProvider for where and how
-- that final, single rounding step happens).
--
-- Same-currency (identity) pairs are deliberately never seeded or looked up
-- here: FxRateProvider implementations must treat fromCurrency == toCurrency
-- as an unconditional identity conversion in code (rate = 1, no DB lookup),
-- not something that depends on a self-referential row existing in this
-- table. See DbFxRateProvider's class-level javadoc for the full reasoning.
CREATE TABLE fx_rates (
    id            UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    from_currency VARCHAR(3)     NOT NULL,
    to_currency   VARCHAR(3)     NOT NULL,
    rate          NUMERIC(19, 8) NOT NULL,
    effective_at  TIMESTAMPTZ    NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_fx_rates_rate_positive CHECK (rate > 0),
    CONSTRAINT chk_fx_rates_distinct_currencies CHECK (from_currency <> to_currency),
    CONSTRAINT uq_fx_rates_pair_effective_at UNIQUE (from_currency, to_currency, effective_at)
);

-- Supports the lookup rule used by DbFxRateProvider: "the most recent row
-- for this pair with effective_at <= now()" - i.e.
--   ... WHERE from_currency = ? AND to_currency = ? AND effective_at <= ?
--   ORDER BY effective_at DESC LIMIT 1
-- The DESC ordering on the index lets Postgres satisfy that query with a
-- single index-order scan (first matching row) instead of a sort.
CREATE INDEX idx_fx_rates_pair_effective_at ON fx_rates (from_currency, to_currency, effective_at DESC);

-- Static, illustrative seed data - NOT live market rates. Effective-dated
-- in the past (well before this migration can ever run) so every seeded
-- rate is immediately usable. Both directions of each pair are seeded
-- explicitly (DbFxRateProvider never triangulates/inverts a rate that is
-- only seeded in the opposite direction), matching the base currency
-- default of USD (see ledger.fx.base-currency in application.yml) plus
-- enough cross rates to demonstrate non-USD-to-non-USD conversion too.
INSERT INTO fx_rates (from_currency, to_currency, rate, effective_at) VALUES
    ('EUR', 'USD', 1.08000000, '2026-01-01T00:00:00Z'),
    ('USD', 'EUR', 0.92592593, '2026-01-01T00:00:00Z'),
    ('GBP', 'USD', 1.27000000, '2026-01-01T00:00:00Z'),
    ('USD', 'GBP', 0.78740157, '2026-01-01T00:00:00Z'),
    ('JPY', 'USD', 0.00670000, '2026-01-01T00:00:00Z'),
    ('USD', 'JPY', 149.25373134, '2026-01-01T00:00:00Z'),
    ('EUR', 'GBP', 0.85039370, '2026-01-01T00:00:00Z'),
    ('GBP', 'EUR', 1.17592593, '2026-01-01T00:00:00Z');
