-- Enforce, at the DB level, that every transaction's entries are balanced:
-- sum(amount WHERE direction = 'DEBIT') = sum(amount WHERE direction = 'CREDIT')
-- for every transaction_id that has at least one entry.
--
-- A single entry row is never balanced on its own - a balanced transaction
-- requires >= 2 entry rows to exist. So the check cannot run as a normal
-- (immediate) AFTER INSERT trigger fired after the first row, or it would
-- reject every transaction's first entry. Instead this uses a DEFERRABLE
-- INITIALLY DEFERRED constraint trigger: PostgreSQL queues the check and
-- runs it once at COMMIT (or at SET CONSTRAINTS ... IMMEDIATE), after all
-- entry rows for the transaction have been inserted in the same DB
-- transaction. This lets application code INSERT each entry row
-- individually (simple, one row at a time) while still getting an
-- all-or-nothing guarantee: an unbalanced set of entries can never be
-- committed, full stop, regardless of what application code does or
-- forgets to do.
--
-- Also fires on DELETE (defense in depth): if entries' immutability trigger
-- (V4) were ever bypassed - e.g. a superuser running
-- `ALTER TABLE entries DISABLE TRIGGER trg_entries_immutable`  - a DELETE
-- that leaves a transaction unbalanced would still be caught here.
CREATE FUNCTION check_transaction_balance() RETURNS trigger AS
$$
DECLARE
    v_transaction_id UUID;
    v_debit_sum      NUMERIC(19, 4);
    v_credit_sum     NUMERIC(19, 4);
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_transaction_id := OLD.transaction_id;
    ELSE
        v_transaction_id := NEW.transaction_id;
    END IF;

    SELECT
        COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
        COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
    INTO v_debit_sum, v_credit_sum
    FROM entries
    WHERE transaction_id = v_transaction_id;

    IF v_debit_sum <> v_credit_sum THEN
        RAISE EXCEPTION
            'transaction % is not balanced: debit total % != credit total % (checked at commit)',
            v_transaction_id, v_debit_sum, v_credit_sum
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL; -- return value ignored for AFTER triggers
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_check_transaction_balance
    AFTER INSERT OR DELETE
    ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION check_transaction_balance();

COMMENT ON TRIGGER trg_check_transaction_balance ON entries IS
    'Deferred constraint trigger: at COMMIT, verifies sum(DEBIT) = sum(CREDIT) '
    'per transaction_id across all entries inserted/deleted in the '
    'transaction. Rejects the whole DB transaction if any ledger '
    'transaction_id is left unbalanced.';
