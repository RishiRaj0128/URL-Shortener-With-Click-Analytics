package com.urlshortener.service;

import com.urlshortener.controller.RedirectController;
import com.urlshortener.dto.ClickEvent;
import com.urlshortener.entity.Url;
import com.urlshortener.pipeline.ClickEventChannel;
import com.urlshortener.security.TraceContextFilter;
import com.urlshortener.util.SnowflakeIdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test suite for Phase 5 Observability & Distributed Tracing.
 * 
 * Verifies:
 * - W3C traceparent and X-Trace-Id parsing and MDC context extraction
 * - TraceContextFilter header propagation to responses
 * - Trace ID propagation from HTTP redirect to ClickEvent queue payload
 * - Micrometer redirect.latency timer and redirect.requests counters
 */
@ExtendWith(MockitoExtension.class)
class ObservabilityAndTracingTest {

    @Mock
    private UrlShorteningService urlShorteningService;

    @Mock
    private ClickService clickService;

    @Mock
    private ClickEventChannel clickEventChannel;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Test
    @DisplayName("TraceContextFilter should parse W3C traceparent and bind traceId to MDC and response")
    void shouldParseW3cTraceparent() throws ServletException, IOException {
        TraceContextFilter filter = new TraceContextFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String incomingTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        request.addHeader(TraceContextFilter.TRACEPARENT_HEADER, "00-" + incomingTraceId + "-00f067aa0ba902b7-01");

        FilterChain mockChain = (req, res) -> {
            // Verify MDC inside filter chain
            assertEquals(incomingTraceId, MDC.get(TraceContextFilter.TRACE_ID_KEY));
            assertEquals(incomingTraceId, req.getAttribute(TraceContextFilter.TRACE_ID_KEY));
        };

        filter.doFilter(request, response, mockChain);

        // Verify response headers and MDC cleanup
        assertEquals(incomingTraceId, response.getHeader(TraceContextFilter.X_TRACE_ID_HEADER));
        assertTrue(response.getHeader(TraceContextFilter.TRACEPARENT_HEADER).contains(incomingTraceId));
        assertNull(MDC.get(TraceContextFilter.TRACE_ID_KEY), "MDC traceId must be cleared after request");
    }

    @Test
    @DisplayName("TraceContextFilter should generate 32-char hex trace ID when header is absent")
    void shouldGenerateTraceIdWhenHeaderAbsent() throws ServletException, IOException {
        TraceContextFilter filter = new TraceContextFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain mockChain = (req, res) -> {
            String mdcTrace = MDC.get(TraceContextFilter.TRACE_ID_KEY);
            assertNotNull(mdcTrace);
            assertEquals(32, mdcTrace.length());
        };

        filter.doFilter(request, response, mockChain);

        String responseTraceId = response.getHeader(TraceContextFilter.X_TRACE_ID_HEADER);
        assertNotNull(responseTraceId);
        assertEquals(32, responseTraceId.length());
    }

    @Test
    @DisplayName("RedirectController should record Micrometer latency timer, status counter, and propagate traceId")
    void shouldRecordMetricsAndPropagateTraceId() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RedirectController controller = new RedirectController(
            urlShorteningService,
            clickService,
            clickEventChannel,
            snowflakeIdGenerator,
            meterRegistry
        );

        Url activeUrl = new Url();
        activeUrl.setId(99L);
        activeUrl.setShortCode("metricLink");
        activeUrl.setOriginalUrl("https://example.com/dest");
        activeUrl.setDeleted(false);

        when(urlShorteningService.getUrlByShortCode("metricLink")).thenReturn(activeUrl);
        when(snowflakeIdGenerator.nextId()).thenReturn(987654321L);
        when(clickService.getClientIp(any(HttpServletRequest.class))).thenReturn("198.51.100.22");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceContextFilter.TRACE_ID_KEY, "trace_abc123456789");

        ResponseEntity<Void> response = controller.redirect("metricLink", request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());

        // Verify Micrometer counter
        double redirectCount = meterRegistry.get("redirect.requests")
            .tag("status", "302")
            .counter()
            .count();
        assertEquals(1.0, redirectCount);

        // Verify Micrometer latency timer recorded
        long timerCount = meterRegistry.get("redirect.latency")
            .tag("status", "302")
            .timer()
            .count();
        assertEquals(1L, timerCount);

        // Verify ClickEvent received traceId
        ArgumentCaptor<ClickEvent> eventCaptor = ArgumentCaptor.forClass(ClickEvent.class);
        verify(clickEventChannel).publish(eventCaptor.capture());

        ClickEvent publishedEvent = eventCaptor.getValue();
        assertEquals("trace_abc123456789", publishedEvent.traceId());
        assertEquals("metricLink", publishedEvent.shortCode());
    }
}
