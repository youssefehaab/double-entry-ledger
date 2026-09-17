package com.ledger.service.service.exception;

/**
 * Thrown when an {@code Idempotency-Key} that was already used for a
 * successfully posted transaction is reused with a request body
 * (description and/or entries) that does not match the original request.
 * Maps to HTTP 409 Conflict.
 *
 * <p>A key reused with the *same* body is a legitimate retry/replay and
 * must not throw this - see {@code TransactionService} for the
 * fingerprint-based comparison that distinguishes a replay from a genuine
 * conflict.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("Idempotency-Key '" + idempotencyKey
                + "' was already used with a different request body");
    }
}
