package com.ledger.service.service;

import com.ledger.service.api.dto.TransactionResponse;

/**
 * Result of {@link TransactionService#createTransaction}, carrying both the
 * response body and whether this was a fresh creation or an idempotent
 * replay - the controller uses {@code replay} to choose between 201
 * Created (first time) and 200 OK (replay of an already-posted
 * transaction). See {@code TransactionController} for the documented
 * status-code decision.
 */
public record TransactionOutcome(TransactionResponse response, boolean replay) {

    public static TransactionOutcome created(TransactionResponse response) {
        return new TransactionOutcome(response, false);
    }

    public static TransactionOutcome replay(TransactionResponse response) {
        return new TransactionOutcome(response, true);
    }
}
