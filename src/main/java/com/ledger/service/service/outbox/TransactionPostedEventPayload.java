package com.ledger.service.service.outbox;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ledger.service.domain.Entry;
import com.ledger.service.domain.EntryDirection;
import com.ledger.service.domain.Transaction;
import com.ledger.service.domain.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The wire shape of a {@code TransactionPosted} outbox event - i.e. exactly
 * what {@code OutboxEventFactory} serializes (via Jackson) into {@code
 * outbox_events.payload}, and exactly what a real downstream consumer would
 * deserialize. Deliberately a separate type from {@link
 * com.ledger.service.api.dto.TransactionResponse}: that DTO is the public
 * HTTP contract (governed by {@code openapi.yaml}) and can evolve for
 * API-shaped reasons independent of what this event needs to carry, even
 * though today the two happen to carry similar fields.
 *
 * <p>Carries each entry's account currency (not just its
 * {@code baseCurrencyAmount}/{@code fxRateUsed}) precisely so a downstream
 * consumer never has to call back into this service (or any other) just to
 * find out what currency an entry's native {@code amount} was posted in.
 */
public record TransactionPostedEventPayload(
        UUID transactionId,
        String idempotencyKey,
        TransactionStatus status,
        Instant postedAt,
        List<EntryPayload> entries
) {

    public static TransactionPostedEventPayload from(Transaction transaction, List<Entry> entries) {
        return new TransactionPostedEventPayload(
                transaction.getId(),
                transaction.getIdempotencyKey(),
                transaction.getStatus(),
                transaction.getPostedAt(),
                entries.stream().map(EntryPayload::from).toList());
    }

    public record EntryPayload(
            UUID accountId,
            BigDecimal amount,
            String currency,
            EntryDirection direction,
            BigDecimal baseCurrencyAmount,
            BigDecimal fxRateUsed,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant fxRateEffectiveAt
    ) {

        public static EntryPayload from(Entry entry) {
            return new EntryPayload(
                    entry.getAccount().getId(),
                    entry.getAmount(),
                    entry.getAccount().getCurrency(),
                    entry.getDirection(),
                    entry.getBaseCurrencyAmount(),
                    entry.getFxRateUsed(),
                    entry.getFxRateEffectiveAt());
        }
    }
}
