-- Entries: the individual debit/credit postings that make up a transaction.
--
-- Money is NUMERIC everywhere, never float/double, in both SQL and the Java
-- mapping (BigDecimal) - float/double introduce binary rounding error that
-- is unacceptable for a ledger.
--
-- Judgment call: amount is stored unsigned (CHECK amount > 0) with a
-- separate `direction` column (DEBIT/CREDIT), rather than a signed amount.
-- This is the conventional double-entry representation and it is what makes
-- the balance trigger in V5 a simple "sum(debit) == sum(credit)"; it also
-- rules out the degenerate case of a "balanced" transaction made of
-- all-zero or mixed-sign entries that would net to zero without actually
-- being balanced in the accounting sense.
--
-- Judgment call: NUMERIC(19,4) fixed precision/scale for all currencies.
-- Real multi-currency systems vary decimal precision per currency (e.g. JPY
-- has 0 minor units, most others have 2, a few have 3). Per-currency
-- precision is FX/currency-metadata logic, explicitly out of scope for this
-- phase (currency is just a stored code) - flagged here so it is not
-- mistaken for an oversight later.
CREATE TABLE entries (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID           NOT NULL REFERENCES transactions (id),
    account_id     UUID           NOT NULL REFERENCES accounts (id),
    amount         NUMERIC(19, 4) NOT NULL,
    direction      VARCHAR(10)    NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT chk_entries_direction
        CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_entries_amount_positive
        CHECK (amount > 0)
);

COMMENT ON TABLE entries IS
    'Append-only ledger postings. Immutability (no UPDATE/DELETE, ever) is '
    'enforced at the DB level in V4. The debit/credit balance invariant per '
    'transaction_id is enforced at the DB level in V5, via a deferred '
    'constraint trigger checked at COMMIT.';

CREATE INDEX idx_entries_transaction_id ON entries (transaction_id);
CREATE INDEX idx_entries_account_id ON entries (account_id);
