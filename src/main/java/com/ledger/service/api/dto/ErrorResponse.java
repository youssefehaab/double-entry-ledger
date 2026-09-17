package com.ledger.service.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Uniform error body for the API layer.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        List<String> messages
) {

    public static ErrorResponse of(int status, String error, List<String> messages) {
        return new ErrorResponse(Instant.now(), status, error, messages);
    }
}
