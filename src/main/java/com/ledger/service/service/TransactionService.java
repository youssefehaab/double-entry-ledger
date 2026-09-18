package com.ledger.service.service;

import com.ledger.service.api.dto.CreateTransactionRequest;
import com.ledger.service.api.dto.TransactionResponse;
import com.ledger.service.service.exception.IdempotencyKeyConflictException;
import com.ledger.service.service.exception.TransactionNotFoundException;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates {@code POST /transactions} and {@code GET /transactions/{id}}.
 *
 * <p>Deliberately <b>not</b> itself {@code @Transactional}: it needs to
 * catch a {@link DataIntegrityViolationException} thrown from one DB
 * transaction ({@link TransactionWriter#createAndPersist}) and then run a
 * completely separate DB transaction to recover
 * ({@link TransactionWriter#findByIdempotencyKey}). See the javadoc on
 * {@link TransactionWriter} for why that requires two distinct calls
 * through the Spring proxy rather than one method wrapping both.
 *
 * <h2>Idempotency mechanism (read-before-write + UNIQUE-constraint backstop)</h2>
 * <ol>
 *   <li><b>First request with a new key:</b> {@code findByIdempotencyKey}
 *       returns empty, and the transaction + entries are inserted in one DB
 *       transaction. No write is attempted before the read-before-write
 *       check passes. Balance validation ({@link TransactionBalanceValidator})
 *       and account-existence validation both still happen before any
 *       {@code Entry} row is written, but have moved inside {@link
 *       TransactionWriter#createAndPersist} (as of Phase 1 of v1 -&gt; v1.1,
 *       multi-currency support) rather than happening here first: a
 *       currency-aware balance check needs each entry's account currency,
 *       which is only known once accounts are loaded/locked inside that
 *       method - see its javadoc and {@link TransactionBalanceValidator}'s
 *       for the full reasoning.</li>
 *   <li><b>Replay with the same key, same body:</b> {@code
 *       findByIdempotencyKey} finds the row from step 1 immediately - the
 *       write path ({@link TransactionWriter#createAndPersist}) is never
 *       called at all, so replay does not reprocess, does not write new
 *       entries, and does not create a duplicate transaction. The original
 *       result is returned with HTTP 200 (see {@code TransactionController}
 *       for the 200-vs-201 decision).</li>
 *   <li><b>Reuse with the same key, different body:</b> same
 *       read-before-write path, but the fingerprint comparison in {@link
 *       IdempotencyRequestMatcher} fails, so {@link
 *       IdempotencyKeyConflictException} is thrown (HTTP 409) instead of
 *       either reprocessing or silently returning the old result as if it
 *       matched.</li>
 *   <li><b>Concurrent replay of a brand-new key (race):</b> two requests
 *       can both see {@code findByIdempotencyKey} return empty before
 *       either has committed. Both attempt {@code createAndPersist}; the
 *       {@code uq_transactions_idempotency_key} UNIQUE constraint (checked
 *       immediately at INSERT, not deferred) guarantees at most one
 *       succeeds. The loser's insert is rolled back and it never gets a
 *       raw 500: this service catches the resulting {@link
 *       DataIntegrityViolationException}, confirms (via SQLState 23505 +
 *       constraint name) that it really is the idempotency-key
 *       constraint and not some other DB error, then re-reads the winner's
 *       row and runs the exact same replay-or-conflict comparison as case 2
 *       or 3 above. This is a real read-before-write check with a real DB
 *       constraint backstop for the race - not a try/catch that pretends a
 *       write never happened; the loser's transaction row genuinely never
 *       commits. A finer-grained fix (e.g. an advisory lock keyed on the
 *       idempotency key, to avoid the loser's wasted insert attempt
 *       entirely) is flagged as a Phase 3 concurrency optimization, not
 *       required for correctness now.</li>
 * </ol>
 */
@Service
public class TransactionService {

    private final TransactionWriter transactionWriter;

    public TransactionService(TransactionWriter transactionWriter) {
        this.transactionWriter = transactionWriter;
    }

    public TransactionOutcome createTransaction(CreateTransactionRequest request, String idempotencyKey) {
        Optional<TransactionResponse> existing = transactionWriter.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return resolveAgainstExisting(existing.get(), request, idempotencyKey);
        }

        // Balance validation happens inside transactionWriter.createAndPersist
        // now (see TransactionWriter/TransactionBalanceValidator javadoc for
        // why: it requires each entry's account currency, which is only
        // known once accounts are loaded there). The DB-level deferred
        // constraint trigger (V9 migration) remains the ultimate backstop
        // regardless.
        try {
            TransactionResponse created = transactionWriter.createAndPersist(request, idempotencyKey);
            return TransactionOutcome.created(created);
        } catch (DataIntegrityViolationException ex) {
            if (!isIdempotencyKeyUniqueViolation(ex)) {
                // Some other DB constraint failed (e.g. the deferred
                // balance trigger caught something the app-level check
                // missed) - not a replay scenario, let it propagate.
                throw ex;
            }
            TransactionResponse winner = transactionWriter.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> ex);
            return resolveAgainstExisting(winner, request, idempotencyKey);
        }
    }

    public TransactionResponse getTransaction(UUID transactionId) {
        return transactionWriter.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }

    private TransactionOutcome resolveAgainstExisting(
            TransactionResponse existing, CreateTransactionRequest request, String idempotencyKey) {
        if (IdempotencyRequestMatcher.matches(existing, request)) {
            return TransactionOutcome.replay(existing);
        }
        throw new IdempotencyKeyConflictException(idempotencyKey);
    }

    /**
     * Distinguishes "the idempotency_key UNIQUE constraint lost a race" from
     * any other {@link DataIntegrityViolationException} (e.g. a foreign key
     * or the deferred balance-check constraint trigger), by inspecting the
     * underlying SQLSTATE (23505 = unique_violation) and constraint name,
     * rather than assuming every DataIntegrityViolationException here means
     * "someone else already has this key."
     */
    private boolean isIdempotencyKeyUniqueViolation(DataIntegrityViolationException ex) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(ex);
        if (root instanceof SQLException sqlEx) {
            return "23505".equals(sqlEx.getSQLState())
                    && sqlEx.getMessage() != null
                    && sqlEx.getMessage().contains("uq_transactions_idempotency_key");
        }
        return false;
    }
}
