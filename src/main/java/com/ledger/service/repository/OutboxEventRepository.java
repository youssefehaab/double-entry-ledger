package com.ledger.service.repository;

import com.ledger.service.domain.OutboxEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Locks and returns exactly one PENDING row that is eligible for a
     * relay attempt right now, or empty if none is eligible.
     *
     * <h2>Eligibility</h2>
     * {@code status = 'PENDING'} and either it has never been attempted
     * ({@code last_attempt_at IS NULL}) or enough wall-clock time has
     * passed since its last attempt: {@code last_attempt_at <= now() -
     * min(:maxBackoffSeconds, :baseBackoffSeconds * 2^attempt_count)}
     * seconds. This is the exponential-backoff formula computed directly in
     * SQL (rather than filtering candidates in application code after a
     * broader select) so ineligible-but-still-PENDING rows are never even
     * locked by this query. Oldest-eligible-first ({@code ORDER BY
     * created_at}) so a persistently failing row does not starve
     * otherwise-healthy rows behind it in the queue indefinitely - it is
     * simply skipped (not locked) until its own backoff window opens.
     *
     * <h2>Concurrency - why {@code FOR UPDATE SKIP LOCKED} + {@code LIMIT 1}</h2>
     * {@code FOR UPDATE} locks the returned row for the duration of the
     * caller's transaction; {@code SKIP LOCKED} means a second, concurrent
     * relay pass (a second poll invocation, or in a future multi-instance
     * deployment, a second instance entirely) racing this same query never
     * blocks on a row another relay pass already has locked, and never
     * selects it either - it moves on to the next eligible row instead.
     * Combined with {@code OutboxRelay} always locking, publishing, AND
     * updating status within one single {@code @Transactional} call (never
     * splitting select and update across two transactions - see that
     * class's javadoc), this is what makes "two concurrent relay passes
     * never publish the same row twice" provable rather than assumed: the
     * row is unavailable to any other transaction from the moment it is
     * locked until this transaction's update to its status has committed
     * (or the whole attempt has rolled back, at which point it is
     * PENDING/unlocked again and fair game for the next pass).
     * {@code LIMIT 1} keeps the lock's blast radius to a single row per
     * transaction (see {@code OutboxRelay} for why per-row transactions were
     * chosen over locking a whole batch at once).
     */
    @Query(
            value = """
                    SELECT * FROM outbox_events
                    WHERE status = 'PENDING'
                      AND (
                        last_attempt_at IS NULL
                        OR last_attempt_at <= now() - (
                            LEAST(:maxBackoffSeconds, :baseBackoffSeconds * POWER(2, attempt_count))
                            * INTERVAL '1 second'
                        )
                      )
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                    """,
            nativeQuery = true)
    Optional<OutboxEvent> lockNextEligibleForRelay(
            @Param("baseBackoffSeconds") long baseBackoffSeconds,
            @Param("maxBackoffSeconds") long maxBackoffSeconds);
}
