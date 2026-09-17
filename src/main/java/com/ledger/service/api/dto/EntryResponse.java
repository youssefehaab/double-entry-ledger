package com.ledger.service.api.dto;

import com.ledger.service.domain.Entry;
import com.ledger.service.domain.EntryDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A single, immutable debit or credit posting")
public record EntryResponse(
        UUID id,
        UUID transactionId,
        UUID accountId,
        BigDecimal amount,
        EntryDirection direction,
        Instant createdAt
) {

    public static EntryResponse from(Entry entry) {
        return new EntryResponse(
                entry.getId(),
                entry.getTransaction().getId(),
                entry.getAccount().getId(),
                entry.getAmount(),
                entry.getDirection(),
                entry.getCreatedAt()
        );
    }
}
