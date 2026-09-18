package com.ledger.service.service.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.service.domain.Entry;
import com.ledger.service.domain.OutboxEvent;
import com.ledger.service.domain.Transaction;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Builds the single {@link OutboxEvent} row {@code TransactionWriter}
 * inserts, in the same DB transaction as the {@link Transaction}/{@link
 * Entry} rows it describes, every time a transaction posts.
 *
 * <p>Owns the one place {@code payload} JSON is actually produced
 * ({@link ObjectMapper#writeValueAsString}), using the Spring-managed,
 * auto-configured {@link ObjectMapper} bean (already on the classpath via
 * {@code spring-boot-starter-web} -&gt; {@code spring-boot-starter-json},
 * which also registers the JSR-310 module Jackson needs to serialize
 * {@link java.time.Instant} as ISO-8601 text) - see {@link OutboxEvent}'s
 * class-level javadoc for why serialization is explicit here rather than
 * delegated to Hibernate's own JSON type support.
 */
@Component
public class OutboxEventFactory {

    private static final String AGGREGATE_TYPE_TRANSACTION = "TRANSACTION";
    private static final String EVENT_TYPE_TRANSACTION_POSTED = "TransactionPosted";

    private final ObjectMapper objectMapper;

    public OutboxEventFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @throws IllegalStateException if the payload cannot be serialized to
     *         JSON - not expected in practice ({@link
     *         TransactionPostedEventPayload} is a plain record of Jackson-
     *         friendly types), but if it ever happened, failing loudly here
     *         (before the DB insert, inside the same transaction as the
     *         entries it would have described) is correct: silently
     *         skipping the outbox row would let a transaction post with no
     *         event ever recorded for it, which is exactly the atomicity
     *         gap this pattern exists to close.
     */
    public OutboxEvent transactionPosted(Transaction transaction, List<Entry> entries) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        Objects.requireNonNull(entries, "entries must not be null");

        TransactionPostedEventPayload payload = TransactionPostedEventPayload.from(transaction, entries);
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "failed to serialize TransactionPosted outbox payload for transaction " + transaction.getId(), e);
        }

        return new OutboxEvent(AGGREGATE_TYPE_TRANSACTION, transaction.getId(), EVENT_TYPE_TRANSACTION_POSTED, json);
    }
}
