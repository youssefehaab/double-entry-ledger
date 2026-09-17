package com.ledger.service.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns a correlation id to every incoming request and makes it available,
 * via SLF4J's {@link MDC}, to every log line emitted while that request is
 * being handled - across the controller, service, and repository layers,
 * regardless of which class actually logs.
 *
 * <p>This is deliberately <b>not</b> a full distributed-tracing integration
 * (no Micrometer Tracing bridge, no exporter, no span model): Phase 4 only
 * calls for "a trace/request id... present in structured logs and
 * consistent across a single request's log lines", which a plain MDC-scoped
 * id fully satisfies without pulling in a tracing backend this service has
 * nowhere to send data to. If/when real distributed tracing is added later
 * (e.g. OpenTelemetry across multiple services), this filter's {@code
 * requestId} can stay as a stable, service-local id alongside whatever
 * {@code traceId}/{@code spanId} a tracing bridge contributes to MDC - they
 * are not mutually exclusive.
 *
 * <h2>Correlation id source</h2>
 * If the caller supplies {@code X-Request-Id}, it is reused as-is (so a
 * caller/gateway that already assigns request ids gets its own id reflected
 * straight through the logs, enabling correlation with upstream systems).
 * Otherwise a fresh random UUID is generated. Either way the id is echoed
 * back on the response as {@code X-Request-Id} so a client can report it
 * when asking for help debugging a specific call.
 *
 * <h2>Two MDC keys, same value</h2>
 * Both {@code requestId} and {@code traceId} are set to the same value:
 * {@code requestId} is this service's own name for it; {@code traceId} is
 * populated too so structured-log tooling/dashboards that expect the
 * conventional Micrometer Tracing MDC key name still line rows up correctly
 * even though no tracing bridge is wired up.
 *
 * <p>Registered as a plain {@code @Component} implementing {@link
 * jakarta.servlet.Filter} (via {@link OncePerRequestFilter}) rather than
 * a {@code FilterRegistrationBean}, and ordered first ({@link
 * Ordered#HIGHEST_PRECEDENCE}) so the id is present in MDC for the entire
 * request lifecycle, including exception handling in {@code
 * GlobalExceptionHandler}. The {@code finally} block always clears MDC
 * after the request completes - required because servlet containers reuse
 * worker threads across requests, so a value left in MDC would otherwise
 * leak into the next, unrelated request handled by the same thread.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_MDC_KEY = "requestId";
    public static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request);
        MDC.put(REQUEST_ID_MDC_KEY, correlationId);
        MDC.put(TRACE_ID_MDC_KEY, correlationId);
        response.setHeader(REQUEST_ID_HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID_MDC_KEY);
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        if (supplied != null && !supplied.isBlank()) {
            return supplied.trim();
        }
        return UUID.randomUUID().toString();
    }
}
