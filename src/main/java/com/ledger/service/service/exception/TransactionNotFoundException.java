package com.ledger.service.service.exception;

import java.util.UUID;

/** Thrown by {@code GET /transactions/{id}} when no transaction has that id. Maps to HTTP 404. */
public class TransactionNotFoundException extends RuntimeException {

    public TransactionNotFoundException(UUID transactionId) {
        super("transaction not found: " + transactionId);
    }
}
