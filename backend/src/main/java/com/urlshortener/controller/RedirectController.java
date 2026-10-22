package com.urlshortener.controller;

import com.urlshortener.dto.ClickEvent;
import com.urlshortener.entity.Url;
import com.urlshortener.pipeline.ClickEventChannel;
import com.urlshortener.security.TraceContextFilter;
import com.urlshortener.service.ClickService;
import com.urlshortener.service.UrlShorteningService;
import com.urlshortener.util.Base62Encoder;
import com.urlshortener.util.SnowflakeIdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * REST controller for redirect endpoint.
 * 
 * Performance critical:
 * - Redis caching ensures O(1) lookup
 * - Non-blocking event dispatch to ClickEventChannel (< 5 microseconds)
 * - Trace context propagation via W3C/MDC
 * - Micrometer latency timer and request counters
 */
@RestController
public class RedirectController {
    
    private final UrlShorteningService urlShorteningService;
    private final ClickService clickService;
    private final ClickEventChannel clickEventChannel;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final MeterRegistry meterRegistry;
    
    @Autowired
    public RedirectController(
            UrlShorteningService urlShorteningService,
            ClickService clickService,
            ClickEventChannel clickEventChannel,
            SnowflakeIdGenerator snowflakeIdGenerator,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        this.urlShorteningService = urlShorteningService;
        this.clickService = clickService;
        this.clickEventChannel = clickEventChannel;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Backward-compatible constructor for unit testing.
     */
    public RedirectController(
            UrlShorteningService urlShorteningService,
            ClickService clickService,
            ClickEventChannel clickEventChannel,
            SnowflakeIdGenerator snowflakeIdGenerator) {
        this(urlShorteningService, clickService, clickEventChannel, snowflakeIdGenerator, null);
    }
    
    /**
     * Redirect to the original URL for a short code.
     * 
     * 1. Look up short code in Redis cache (via @Cacheable in service)
     * 2. If cache miss, query PostgreSQL
     * 3. Verify link is not expired or soft-deleted (evicts stale cache if found)
     * 4. Publish ClickEvent with distributed trace ID to async ingestion channel
     * 5. Record Micrometer latency and request counters
     * 6. Return 302 redirect immediately (< 5ms response time)
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(
            @PathVariable String shortCode,
            HttpServletRequest request) {
        
        Timer.Sample sample = (meterRegistry != null) ? Timer.start(meterRegistry) : null;

        try {
            // Look up URL (cached in Redis)
            Url url = urlShorteningService.getUrlByShortCode(shortCode);

            // Verification in fast path protects against serving cached entries that expired post-cache
            if (url == null || url.isDeleted() || url.isExpired()) {
                urlShorteningService.evictCache(shortCode);
                if (meterRegistry != null) {
                    meterRegistry.counter("redirect.requests", "status", "410").increment();
                }
                return ResponseEntity.status(HttpStatus.GONE).build();
            }
            
            // Extract headers in the request thread to avoid Tomcat recycling issues
            String userAgent = request.getHeader("User-Agent");
            String referrer = request.getHeader("Referer");
            String clientIp = clickService.getClientIp(request);
            String traceId = (String) request.getAttribute(TraceContextFilter.TRACE_ID_KEY);
            
            // Generate unique distributed event ID for idempotent deduplication
            String eventId = "evt_" + Base62Encoder.encode(snowflakeIdGenerator.nextId());
            ClickEvent clickEvent = new ClickEvent(
                eventId,
                shortCode,
                url.getId(),
                LocalDateTime.now(),
                clientIp,
                userAgent,
                referrer,
                traceId
            );
            
            // Non-blocking publish to async ingestion channel
            clickEventChannel.publish(clickEvent);
            
            // Return 302 redirect immediately with normalized absolute URL
            String destination = url.getOriginalUrl();
            if (destination != null) {
                destination = destination.trim();
                if (!destination.startsWith("http://") && !destination.startsWith("https://")) {
                    destination = "https://" + destination;
                }
            }

            if (meterRegistry != null) {
                meterRegistry.counter("redirect.requests", "status", "302").increment();
            }

            return ResponseEntity
                .status(HttpStatus.FOUND)
                .header("Location", destination)
                .build();
        } finally {
            if (sample != null) {
                sample.stop(meterRegistry.timer("redirect.latency", "status", "302"));
            }
        }
    }
}
