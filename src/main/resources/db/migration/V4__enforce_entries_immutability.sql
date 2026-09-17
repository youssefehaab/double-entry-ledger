-- Enforce that entries are append-only at the DB level: once an entry row is
-- inserted, it can never be UPDATEd or DELETEd, even if application code has
-- a bug and attempts it (or a human runs ad-hoc SQL against the DB).
--
-- Mechanism chosen: a BEFORE UPDATE OR DELETE trigger that unconditionally
-- raises an exception, over the two alternatives:
--   - A rule (CREATE RULE ... DO INSTEAD NOTHING) would silently no-op the
--     UPDATE/DELETE instead of failing loudly. Silent no-ops on a ledger are
--     worse than an error: calling code (or a human at a psql prompt) would
--     see "UPDATE 0 rows" and could easily miss that the mutation was
--     dropped rather than rejected. Rules are also officially considered
--     legacy/discouraged by the PostgreSQL project in favor of triggers.
--   - REVOKE UPDATE, DELETE ON entries FROM app_role would work, but it
--     depends on the application always connecting as a specific
--     non-superuser role and on that role never being granted broader
--     privileges later (e.g. by a future migration or an ops fix). It is a
--     privilege-management concern that lives outside the migration that
--     defines the table, so it is easy for the invariant to silently stop
--     holding without anyone touching this file.
-- A trigger is self-contained in the schema itself, fires regardless of
-- which role runs the statement (short of a superuser explicitly disabling
-- triggers or setting session_replication_role), fails loudly with a clear
-- message, and is trivial to reason about and test.
CREATE FUNCTION prevent_entries_mutation() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION
        'entries is append-only: % on entries.id=% is not permitted (immutable ledger row)',
        TG_OP, OLD.id
        USING ERRCODE = 'raise_exception';
    RETURN NULL; -- unreachable, RAISE EXCEPTION aborts the statement
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_entries_immutable
    BEFORE UPDATE OR DELETE
    ON entries
    FOR EACH ROW
EXECUTE FUNCTION prevent_entries_mutation();

COMMENT ON TRIGGER trg_entries_immutable ON entries IS
    'Rejects any UPDATE or DELETE on entries. Entries are append-only; '
    'corrections must be made with new, offsetting entries in a new '
    'transaction, never by mutating history.';
