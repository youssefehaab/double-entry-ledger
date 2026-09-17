package com.ledger.service.api.dto;

import com.ledger.service.domain.Entry;
import com.ledger.service.domain.Transaction;
import com.ledger.service.domain.TransactionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A posted transaction and its balanced entries")
public record TransactionResponse(
        UUID id,
        String description,
        TransactionStatus status,
        Instant createdAt,
        Instant postedAt,
        List<EntryResponse> entries
) {

    public static TransactionResponse from(Transaction transaction, List<Entry> entries) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getDescription(),
                transaction.getStatus(),
                transaction.getCreatedAt(),
                transaction.getPostedAt(),
                entries.stream().map(EntryResponse::from).toList()
        );
    }
}
