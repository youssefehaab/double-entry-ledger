package com.ledger.service.domain;

/**
 * Mirrors the {@code chk_outbox_events_status} CHECK constraint in
 * {@code V10__create_outbox_events_table.sql}.
 *
 * <p>Transition table (enforced in code by {@link OutboxEvent}, not by a DB
 * trigger - unlike {@link Transaction}/{@code Entry}, outbox rows are
 * legitimately mutated in place by the relay, so there is no immutability
 * guarantee to enforce at the DB level here):
 * <ul>
 *   <li>{@code PENDING -> PUBLISHED}: a successful relay publish
 *       ({@link OutboxEvent#markPublished}).</li>
 *   <li>{@code PENDING -> PENDING}: a failed publish attempt that has not
 *       yet exhausted {@code ledger.outbox.max-attempts}
 *       ({@link OutboxEvent#recordFailedAttempt}) - stays PENDING so the
 *       relay retries it later, after backoff.</li>
 *   <li>{@code PENDING -> FAILED}: a failed publish attempt that has
 *       exhausted the configured max attempts
 *       ({@link OutboxEvent#recordFailedAttempt}) - a dead letter; the relay
 *       never selects FAILED rows again.</li>
 * </ul>
 * There is no {@code PUBLISHED -> *} or {@code FAILED -> *} transition
 * anywhere in this codebase.
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
