-- Transactions: the unit of atomicity for a set of balanced entries.
--
-- Table is named `transactions`, not `transaction`, to avoid colliding with
-- the SQL:1999 reserved-ish keyword TRANSACTION used by BEGIN/COMMIT
-- tooling and some ORMs/clients that treat it specially.
--
-- idempotency_key is UNIQUE + NOT NULL so that retried "create transaction"
-- requests (Phase 2) can safely upsert/no-op via ON CONFLICT (idempotency_key)
-- DO NOTHING/UPDATE instead of creating duplicate money movements.
CREATE TABLE transactions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) NOT NULL,
    description     VARCHAR(1000) NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    posted_at       TIMESTAMPTZ   NULL,

    CONSTRAINT uq_transactions_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_transactions_status
        CHECK (status IN ('PENDING', 'POSTED', 'FAILED')),
    -- A transaction only has a posted_at once it has actually posted.
    CONSTRAINT chk_transactions_posted_at_consistency
        CHECK (
            (status = 'POSTED' AND posted_at IS NOT NULL)
            OR (status <> 'POSTED' AND posted_at IS NULL)
        )
);

COMMENT ON TABLE transactions IS
    'A transaction is the atomic unit that groups a balanced set of entries. '
    'Table named transactions (plural) to avoid colliding with the '
    'TRANSACTION keyword used by SQL client/ORM tooling.';

COMMENT ON COLUMN transactions.idempotency_key IS
    'Client-supplied key, unique across all transactions, used to make '
    'transaction creation safely retryable (Phase 2).';
