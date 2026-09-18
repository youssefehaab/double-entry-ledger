-- Supersedes V5's balance check. V5__enforce_transaction_balance.sql is
-- NEVER edited (per this project's forward-only migration discipline) -
-- this migration replaces its behavior in place instead.
--
-- Why this must change at all: V5 checked sum(amount) per direction, i.e.
-- summed entries' NATIVE-currency amounts directly against each other.
-- That was correct back when every entry was implicitly the same currency
-- (no multi-currency support existed), but raw amounts in different
-- currencies are not meaningfully additive - "100 EUR debit, 100 USD
-- credit" summed as raw numbers happens to look balanced (100 == 100)
-- while being economically nonsense, and a real cross-currency transaction
-- ("100 EUR debit, 108 USD credit" at a 1.08 rate) would be rejected by
-- V5's check as "unbalanced" even though it is exactly balanced once both
-- legs are expressed in one common currency. The fix: check
-- sum(base_currency_amount) - both legs already expressed in the same
-- (base/reporting) currency by TransactionWriter at insert time (see V8
-- for those columns, and DbFxRateProvider for the conversion) - instead of
-- sum(amount).
--
-- Same function name (check_transaction_balance), replaced via CREATE OR
-- REPLACE FUNCTION: this is the same trigger *concept* (a per-transaction,
-- deferred, at-commit balance check), just recomputed on a different column
-- with a signed-sum formula instead of separate DEBIT/CREDIT totals, so
-- reusing the name is accurate, not just convenient. The constraint trigger
-- object itself (trg_check_transaction_balance) is explicitly dropped and
-- recreated here too, even though CREATE OR REPLACE FUNCTION alone would
-- already change its behavior in place (a trigger only stores the function
-- name it calls) - doing so makes this migration fully self-contained and
-- the trigger's current definition traceable to this file rather than
-- silently mutated from within another one, and leaves room for the
-- DEFERRABLE/FOR EACH ROW/AFTER INSERT OR DELETE clauses themselves to
-- diverge from V5 in a future migration without a function-only change
-- being able to express that.
--
-- Sign convention: DEBIT positive, CREDIT negative, matching this service's
-- documented "debit-positive" balance formula (balance = sum(DEBIT) -
-- sum(CREDIT)) - see EntryRepository#sumSignedAmountsByAccountId and the
-- README. A transaction balances iff its entries' signed
-- base_currency_amounts net to exactly zero.
--
-- Exact equality, no epsilon/tolerance (flagged for backend-reviewer): the
-- check below is v_net_base_amount <> 0 at NUMERIC(19,4) scale, with no
-- rounding slack. This is deliberate and is only safe because rounding is
-- centralized to a single point (DbFxRateProvider, at the moment
-- base_currency_amount is computed - see V8's comment) using a fixed
-- scale and RoundingMode.HALF_UP consistently for every entry. Correct,
-- consistent rounding at conversion time is what makes zero-drift exact
-- equality achievable; loosening this check to tolerate a small delta
-- would instead paper over a rounding bug (or a future conversion path
-- that rounds inconsistently) rather than catching it.
--
-- Missing FX data is a hard failure, not a silent pass: if any entry for
-- this transaction has a NULL base_currency_amount (e.g. a legacy V5-era
-- row somehow reused, or a write that bypassed TransactionWriter/the app
-- layer entirely via raw SQL and never populated the FX columns from V8),
-- summing across a NULL would make SUM(...) itself return NULL, and
-- `IF NULL <> 0` evaluates to NULL/false in PL/pgSQL - i.e. the naive
-- version of this check would silently treat missing FX data as "nothing to
-- check" and let an unverified transaction commit. Guarded against
-- explicitly below by counting NULLs first and raising if any are found,
-- so the DB-level balance invariant can never be silently bypassed by
-- omitting the new columns.
CREATE OR REPLACE FUNCTION check_transaction_balance() RETURNS trigger AS
$$
DECLARE
    v_transaction_id     UUID;
    v_missing_fx_count    BIGINT;
    v_net_base_amount     NUMERIC(19, 4);
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_transaction_id := OLD.transaction_id;
    ELSE
        v_transaction_id := NEW.transaction_id;
    END IF;

    SELECT COUNT(*) FILTER (WHERE base_currency_amount IS NULL)
    INTO v_missing_fx_count
    FROM entries
    WHERE transaction_id = v_transaction_id;

    IF v_missing_fx_count > 0 THEN
        RAISE EXCEPTION
            'transaction % has % entr(y/ies) missing base_currency_amount - FX conversion must be recorded at insert time for every entry before the balance can be verified (checked at commit)',
            v_transaction_id, v_missing_fx_count
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT SUM(CASE WHEN direction = 'DEBIT' THEN base_currency_amount ELSE -base_currency_amount END)
    INTO v_net_base_amount
    FROM entries
    WHERE transaction_id = v_transaction_id;

    IF v_net_base_amount <> 0 THEN
        RAISE EXCEPTION
            'transaction % is not balanced: net base_currency_amount % != 0 (checked at commit)',
            v_transaction_id, v_net_base_amount
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_check_transaction_balance ON entries;

CREATE CONSTRAINT TRIGGER trg_check_transaction_balance
    AFTER INSERT OR DELETE
    ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION check_transaction_balance();
