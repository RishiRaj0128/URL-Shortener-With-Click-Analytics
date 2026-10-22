package com.urlshortener.controller;

import com.urlshortener.dto.ClickEvent;
import com.urlshortener.entity.Url;
import com.urlshortener.pipeline.ClickEventChannel;
import com.urlshortener.service.ClickService;
import com.urlshortener.service.UrlShorteningService;
import com.urlshortener.util.Base62Encoder;
import com.urlshortener.util.SnowflakeIdGenerator;
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
 * This is the public-facing redirect endpoint that converts short codes to original URLs.
 * No authentication required.
 * 
 * Endpoint:
 * - GET /{shortCode}: Redirect to original URL and asynchronously publish ClickEvent
 * 
 * Performance critical:
 * - Redis caching ensures O(1) lookup
 * - Non-blocking event dispatch to ClickEventChannel (< 5 microseconds)
 * - Redirect latency NEVER depends on database or analytics writes
 */
@RestController
public class RedirectController {
    
    private final UrlShorteningService urlShorteningService;
    private final ClickService clickService;
    private final ClickEventChannel clickEventChannel;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    
    @Autowired
    public RedirectController(
            UrlShorteningService urlShorteningService,
            ClickService clickService,
            ClickEventChannel clickEventChannel,
            SnowflakeIdGenerator snowflakeIdGenerator) {
        this.urlShorteningService = urlShorteningService;
        this.clickService = clickService;
        this.clickEventChannel = clickEventChannel;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }
    
    /**
     * Redirect to the original URL for a short code.
     * 
     * 1. Look up short code in Redis cache (via @Cacheable in service)
     * 2. If cache miss, query PostgreSQL
     * 3. Publish ClickEvent to high-throughput queue (non-blocking)
     * 4. Return 302 redirect immediately (< 5ms response time)
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(
            @PathVariable String shortCode,
            HttpServletRequest request) {
        
        // Look up URL (cached in Redis)
        Url url = urlShorteningService.getUrlByShortCode(shortCode);
        
        // Extract headers in the request thread to avoid Tomcat recycling issues
        String userAgent = request.getHeader("User-Agent");
        String referrer = request.getHeader("Referer");
        String clientIp = clickService.getClientIp(request);
        
        // Generate unique distributed event ID for idempotent deduplication
        String eventId = "evt_" + Base62Encoder.encode(snowflakeIdGenerator.nextId());
        ClickEvent clickEvent = new ClickEvent(
            eventId,
            shortCode,
            url.getId(),
            LocalDateTime.now(),
            clientIp,
            userAgent,
            referrer
        );
        
        // Non-blocking publish to async ingestion channel
        clickEventChannel.publish(clickEvent);
        
        // Return 302 redirect immediately with normalized absolute URL
        String destination = url.getOriginalUrl();
        if (destination != null) {
            destination = destination.trim();
            if (!destination.startsWith("http://") && !destination.startsWith("https://") && !destination.startsWith("ftp://")) {
                destination = "https://" + destination;
            }
        }
        return ResponseEntity
            .status(HttpStatus.FOUND)
            .header("Location", destination)
            .build();
    }
}
