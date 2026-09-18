-- Phase 2 (v1 -> v1.1): transactional outbox for publishing domain events
-- (starting with TransactionPosted) to Kafka without a dual-write race
-- between "the transaction committed" and "an event describing it was
-- published." TransactionWriter#createAndPersist inserts exactly one row
-- here in the SAME DB transaction as the Transaction/Entry rows it writes -
-- see that method's javadoc. A separate, asynchronous relay process (see
-- OutboxRelay) is the only thing that ever reads PENDING rows here and
-- publishes them to Kafka; the HTTP write path never talks to Kafka
-- directly.
--
-- id has its own DEFAULT gen_random_uuid() (unlike transactions.id/entries.id,
-- which rely on the JPA @GeneratedValue(UUID) strategy to generate the value
-- application-side) purely for consistency with fx_rates.id (V7); either
-- convention works since the entity always sets the id before insert.
CREATE TABLE outbox_events (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    -- Jackson-serialized payload (see OutboxEventFactory), stored as real
    -- jsonb (not text) so it stays queryable/inspectable with normal
    -- Postgres JSON operators if ever needed for ops/debugging.
    payload         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    -- Backoff bookkeeping: NULL until the first publish attempt is made.
    -- OutboxRelay computes each row's next eligible retry time as
    -- last_attempt_at + min(backoff-max-seconds, backoff-base-seconds *
    -- 2^attempt_count) rather than storing a precomputed next_attempt_at
    -- column, so the backoff formula/config can change without a migration.
    last_attempt_at TIMESTAMPTZ,
    CONSTRAINT chk_outbox_events_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT chk_outbox_events_attempt_count_non_negative CHECK (attempt_count >= 0)
);

-- Partial index: the relay's hot query is always "find PENDING rows",
-- and PENDING is expected to be a small, fast-draining minority of the
-- table once PUBLISHED/FAILED rows accumulate - a partial index keeps its
-- size proportional to the backlog, not the full event history, and
-- ordering by created_at lets the relay's SELECT ... ORDER BY created_at
-- ... FOR UPDATE SKIP LOCKED be satisfied by an index scan instead of a
-- sort over the whole table.
CREATE INDEX idx_outbox_events_pending_created_at
    ON outbox_events (created_at)
    WHERE status = 'PENDING';

-- Supports "all outbox events for a given aggregate" lookups (e.g. future
-- debugging/ops tooling, or a read endpoint if one is ever added) without a
-- sequential scan.
CREATE INDEX idx_outbox_events_aggregate ON outbox_events (aggregate_type, aggregate_id);
