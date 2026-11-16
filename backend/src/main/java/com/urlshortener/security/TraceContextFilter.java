package com.urlshortener.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter that establishes distributed tracing context for every inbound HTTP request.
 * 
 * Supports:
 * - W3C TraceContext standard ("traceparent: 00-{trace_id}-{parent_id}-{trace_flags}")
 * - Custom "X-Trace-Id" header
 * - Automatic high-entropy trace ID generation when header is absent
 * - Mapped Diagnostic Context (MDC) binding for correlated logging across async click pipelines
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter implements Filter {

    public static final String TRACE_ID_KEY = "traceId";
    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String X_TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        if (request instanceof HttpServletRequest httpRequest && response instanceof HttpServletResponse httpResponse) {
            String traceId = resolveTraceId(httpRequest);

            MDC.put(TRACE_ID_KEY, traceId);
            httpRequest.setAttribute(TRACE_ID_KEY, traceId);

            httpResponse.setHeader(X_TRACE_ID_HEADER, traceId);
            httpResponse.setHeader(TRACEPARENT_HEADER, "00-" + traceId + "-0000000000000000-01");

            try {
                chain.doFilter(request, response);
            } finally {
                MDC.remove(TRACE_ID_KEY);
            }
        } else {
            chain.doFilter(request, response);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        // 1. Check W3C traceparent: 00-{trace_id}-{parent_id}-{flags}
        String traceparent = request.getHeader(TRACEPARENT_HEADER);
        if (traceparent != null && !traceparent.isBlank()) {
            String[] parts = traceparent.trim().split("-");
            if (parts.length >= 3 && parts[1].length() == 32) {
                return parts[1];
            }
        }

        // 2. Check X-Trace-Id
        String customTraceId = request.getHeader(X_TRACE_ID_HEADER);
        if (customTraceId != null && !customTraceId.isBlank()) {
            return customTraceId.trim();
        }

        // 3. Generate 32-character hex trace ID via lock-free ThreadLocalRandom
        long high = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        long low = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        return String.format("%016x%016x", high, low);
    }
}
