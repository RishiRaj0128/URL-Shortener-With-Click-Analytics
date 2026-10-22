package com.urlshortener.controller;

import com.urlshortener.dto.AnalyticsResponse;
import com.urlshortener.dto.ShortenUrlRequest;
import com.urlshortener.dto.ShortenUrlResponse;
import com.urlshortener.dto.UrlResponse;
import com.urlshortener.service.ClickService;
import com.urlshortener.security.JwtTokenProvider;
import com.urlshortener.service.RateLimitingService;
import com.urlshortener.service.UrlShorteningService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for URL shortening and analytics endpoints.
 * 
 * Endpoints:
 * - POST /api/shorten: Create a shortened URL
 * - GET /api/urls: List user's shortened URLs
 * - GET /api/analytics/{shortCode}: Get analytics for a shortened URL
 * - DELETE /api/urls/{shortCode}: Delete a shortened URL
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*", maxAge = 3600)
public class UrlController {

  private final UrlShorteningService urlShorteningService;
  private final ClickService clickService;
  private final RateLimitingService rateLimitingService;
  private final JwtTokenProvider jwtTokenProvider;

  private final Long workerId;
  private final com.urlshortener.util.ClientIpResolver clientIpResolver;

  @Autowired
  public UrlController(
      UrlShorteningService urlShorteningService,
      ClickService clickService,
      RateLimitingService rateLimitingService,
      JwtTokenProvider jwtTokenProvider,
      @org.springframework.beans.factory.annotation.Value("${app.snowflake.worker-id:1}") Long workerId,
      @Autowired(required = false) com.urlshortener.util.ClientIpResolver clientIpResolver) {
    this.urlShorteningService = urlShorteningService;
    this.clickService = clickService;
    this.rateLimitingService = rateLimitingService;
    this.jwtTokenProvider = jwtTokenProvider;
    this.workerId = workerId;
    this.clientIpResolver = clientIpResolver != null ? clientIpResolver : new com.urlshortener.util.ClientIpResolver("127.0.0.1,::1");
  }

  /**
   * Create a shortened URL.
   * Rate limited to prevent abuse.
   * 
   * @param request ShortenUrlRequest with the original URL
   * @return ShortenUrlResponse with short code and QR code
   */
  @PostMapping(value = {"/shorten", "/urls"})
  public ResponseEntity<ShortenUrlResponse> shortenUrl(
      @Valid @RequestBody ShortenUrlRequest request,
      HttpServletRequest httpRequest) {

    // Rate limiting check
    String clientIp = getClientIp(httpRequest);
    if (!rateLimitingService.allowRequest(clientIp)) {
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
          .build();
    }

    String userEmail = getUserEmail(httpRequest);
    ShortenUrlResponse response = urlShorteningService.shortenUrl(request, userEmail);

    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  /**
   * Get all shortened URLs for the authenticated user.
   * 
   * @return List of user's shortened URLs
   */
  @GetMapping("/urls")
  public ResponseEntity<List<UrlResponse>> getUserUrls(HttpServletRequest request) {
    String userEmail = getUserEmail(request);
    List<UrlResponse> urls = urlShorteningService.getUserUrls(userEmail);
    return ResponseEntity.ok(urls);
  }

  /**
   * Get analytics for a shortened URL.
   * User must own the URL.
   * 
   * @param shortCode The short code of the URL
   * @return Analytics data (clicks, breakdown by country/browser/device/OS, time
   *         series)
   */
  @GetMapping("/analytics/{shortCode}")
  public ResponseEntity<AnalyticsResponse> getAnalytics(
      @PathVariable String shortCode,
      HttpServletRequest request) {
    String userEmail = getUserEmail(request);
    AnalyticsResponse analytics = clickService.getAnalytics(shortCode, userEmail);
    return ResponseEntity.ok(analytics);
  }

  /**
   * Delete a shortened URL.
   * User must own the URL.
   * 
   * @param shortCode The short code of the URL to delete
   */
  @DeleteMapping("/urls/{shortCode}")
  public ResponseEntity<Void> deleteUrl(
      @PathVariable String shortCode,
      HttpServletRequest request) {
    String userEmail = getUserEmail(request);
    urlShorteningService.deleteUrl(shortCode, userEmail);
    return ResponseEntity.noContent().build();
  }

  /**
   * Update an existing shortened URL.
   * Evicts Redis cache-aside entry immediately.
   */
  @PutMapping("/urls/{shortCode}")
  public ResponseEntity<UrlResponse> updateUrl(
      @PathVariable String shortCode,
      @Valid @RequestBody ShortenUrlRequest request,
      HttpServletRequest httpRequest) {
    String userEmail = getUserEmail(httpRequest);
    UrlResponse response = urlShorteningService.updateUrl(shortCode, request.getUrl(), userEmail);
    return ResponseEntity.ok(response);
  }

  /**
   * Health check endpoint (no auth required).
   */
  @GetMapping("/health")
  public ResponseEntity<String> health() {
    return ResponseEntity.ok("OK");
  }

  /**
   * Explicit readiness endpoint for cluster health and node rejoin verification.
   */
  @GetMapping("/health/ready")
  public ResponseEntity<java.util.Map<String, Object>> ready() {
    java.util.Map<String, Object> status = new java.util.LinkedHashMap<>();
    status.put("status", "UP");
    status.put("ready", true);
    status.put("workerId", workerId);
    status.put("timestamp", java.time.Instant.now().toString());
    return ResponseEntity.ok(status);
  }

  /**
   * Extract authenticated user's email from security context.
   */
  private String getUserEmail(HttpServletRequest request) {
    String authorizationHeader = request.getHeader("Authorization");
    if (!StringUtils.hasText(authorizationHeader) || !authorizationHeader.startsWith("Bearer ")) {
      throw new UsernameNotFoundException("Unauthorized");
    }

    String token = authorizationHeader.substring(7);
    if (!jwtTokenProvider.validateToken(token)) {
      throw new UsernameNotFoundException("Unauthorized");
    }

    return jwtTokenProvider.getEmailFromToken(token);
  }

  /**
   * Extract client IP from request with proxy spoofing protection.
   */
  private String getClientIp(HttpServletRequest request) {
    return clientIpResolver.resolveClientIp(request);
  }
}
