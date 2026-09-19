package com.ledger.service.config;

import com.ledger.service.domain.OutboxStatus;
import com.ledger.service.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@code ledger.outbox.backlog.size} gauge: the current
 * number of PENDING rows in {@code outbox_events}.
 *
 * <p>This is registered via {@link Gauge.Builder#register(MeterRegistry)}
 * using the {@code (stateObject, valueFunction)} overload of
 * {@link Gauge#builder(String, Object, java.util.function.ToDoubleFunction)},
 * with {@code outboxEventRepository} itself as the state object and
 * {@code repo -> repo.countByStatus(OutboxStatus.PENDING)} as the value
 * function. Micrometer holds only a weak reference to the state object and
 * calls the value function directly on every scrape (e.g. every hit of
 * {@code GET /actuator/metrics/ledger.outbox.backlog.size}, or every
 * Prometheus scrape if that registry is wired) - it never caches or
 * pre-computes the value. This means the {@code COUNT(*) ... WHERE
 * status = 'PENDING'} query genuinely runs at scrape time, not once at
 * bean-construction/startup time, and not on a {@code @Scheduled} cadence
 * that merely updates a field this gauge reads back.
 */
@Configuration
public class MetricsConfig {

    private final MeterRegistry meterRegistry;
    private final OutboxEventRepository outboxEventRepository;

    public MetricsConfig(MeterRegistry meterRegistry, OutboxEventRepository outboxEventRepository) {
        this.meterRegistry = meterRegistry;
        this.outboxEventRepository = outboxEventRepository;
    }

    @PostConstruct
    void registerOutboxBacklogGauge() {
        Gauge.builder(
                        "ledger.outbox.backlog.size",
                        outboxEventRepository,
                        repo -> repo.countByStatus(OutboxStatus.PENDING))
                .description("Current number of PENDING rows in outbox_events, queried fresh on every scrape")
                .register(meterRegistry);
    }
}
