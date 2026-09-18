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
        @Schema(description = "amount converted into the ledger's base/reporting currency at post time "
                + "(see ledger.fx.base-currency); for a same-currency entry this equals amount exactly")
        BigDecimal baseCurrencyAmount,
        @Schema(description = "the exact FX rate applied to produce baseCurrencyAmount; 1 for a "
                + "same-currency entry")
        BigDecimal fxRateUsed,
        @Schema(description = "when fxRateUsed was/is effective; the conversion instant itself for a "
                + "same-currency entry")
        Instant fxRateEffectiveAt,
        Instant createdAt
) {

    public static EntryResponse from(Entry entry) {
        return new EntryResponse(
                entry.getId(),
                entry.getTransaction().getId(),
                entry.getAccount().getId(),
                entry.getAmount(),
                entry.getDirection(),
                entry.getBaseCurrencyAmount(),
                entry.getFxRateUsed(),
                entry.getFxRateEffectiveAt(),
                entry.getCreatedAt()
        );
    }
}
