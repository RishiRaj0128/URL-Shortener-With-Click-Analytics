package com.urlshortener.util;

import com.urlshortener.service.RateLimitingService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Track 1.3: Client IP Extraction & Reverse Proxy Spoof Protection Tests")
class ClientIpResolverTest {

    private ClientIpResolver resolver;
    private RateLimitingService rateLimitingService;

    @BeforeEach
    void setUp() {
        resolver = new ClientIpResolver("127.0.0.1,::1,172.20.0.1,172.20.0.2,10.0.0.1");
        rateLimitingService = new RateLimitingService();
        ReflectionTestUtils.setField(rateLimitingService, "rateLimitingEnabled", true);
        ReflectionTestUtils.setField(rateLimitingService, "requestsPerMinute", 5);
    }

    @Test
    @DisplayName("Should extract real client IP when request arrives from trusted reverse proxy")
    void shouldExtractRealClientIpFromTrustedProxy() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("172.20.0.2"); // Nginx container IP on Docker network
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50");

        String clientIp = resolver.resolveClientIp(request);
        assertEquals("203.0.113.50", clientIp, "Real client IP must survive Nginx proxy");
    }

    @Test
    @DisplayName("Should parse first client IP from multi-hop X-Forwarded-For header")
    void shouldParseFirstIpFromMultiHopHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1"); // Localhost Nginx
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.77, 10.0.0.1, 127.0.0.1");

        String clientIp = resolver.resolveClientIp(request);
        assertEquals("198.51.100.77", clientIp, "Origin client IP must be extracted from multi-hop proxy chain");
    }

    @Test
    @DisplayName("Security: Should IGNORE spoofed X-Forwarded-For header from untrusted external caller")
    void shouldIgnoreSpoofedHeaderFromUntrustedCaller() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        String attackerDirectIp = "185.220.101.5"; // Direct connection from untrusted internet IP
        when(request.getRemoteAddr()).thenReturn(attackerDirectIp);
        // Attacker attempts to spoof a Google DNS IP to bypass per-IP rate limiting / anomaly detection
        when(request.getHeader("X-Forwarded-For")).thenReturn("8.8.8.8");

        String resolvedIp = resolver.resolveClientIp(request);
        assertEquals(attackerDirectIp, resolvedIp, 
                "Untrusted direct connection must NOT trust X-Forwarded-For; spoof attempt must be ignored!");
    }

    @Test
    @DisplayName("Rate Limiting: Real client IP through proxy receives independent bucket")
    void shouldEnforceRateLimitingOnExtractedRealClientIp() {
        HttpServletRequest proxyReq1 = mock(HttpServletRequest.class);
        when(proxyReq1.getRemoteAddr()).thenReturn("172.20.0.2");
        when(proxyReq1.getHeader("X-Forwarded-For")).thenReturn("103.21.244.1");

        HttpServletRequest proxyReq2 = mock(HttpServletRequest.class);
        when(proxyReq2.getRemoteAddr()).thenReturn("172.20.0.2");
        when(proxyReq2.getHeader("X-Forwarded-For")).thenReturn("103.21.244.2");

        String client1 = resolver.resolveClientIp(proxyReq1);
        String client2 = resolver.resolveClientIp(proxyReq2);

        // Exhaust client1's rate limit budget (5 req/min)
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimitingService.allowRequest(client1, null));
        }
        assertFalse(rateLimitingService.allowRequest(client1, null), "Client 1 should be rate limited after 5 requests");

        // Client 2 arriving through the same Nginx proxy MUST NOT be rate limited!
        assertTrue(rateLimitingService.allowRequest(client2, null), "Client 2 must have an independent rate limit bucket");
    }

    @Test
    @DisplayName("Security: External attacker cannot bypass rate limiting by rotating X-Forwarded-For")
    void shouldPreventRateLimitBypassViaSpoofedXff() {
        String attackerIp = "192.0.2.99";

        // Attacker sends 6 requests from the same direct IP, rotating XFF on each request
        for (int i = 0; i < 5; i++) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            when(req.getRemoteAddr()).thenReturn(attackerIp);
            when(req.getHeader("X-Forwarded-For")).thenReturn("1.2.3." + i); // Fake rotated IPs

            String resolved = resolver.resolveClientIp(req);
            assertEquals(attackerIp, resolved, "XFF must be ignored for untrusted callers");
            assertTrue(rateLimitingService.allowRequest(resolved, null));
        }

        // 6th request with another fake IP must be blocked because real IP is tracked
        HttpServletRequest req6 = mock(HttpServletRequest.class);
        when(req6.getRemoteAddr()).thenReturn(attackerIp);
        when(req6.getHeader("X-Forwarded-For")).thenReturn("1.2.3.99");

        String resolved6 = resolver.resolveClientIp(req6);
        assertFalse(rateLimitingService.allowRequest(resolved6, null), 
                "Attacker must be blocked on their real IP regardless of rotating spoofed XFF headers!");
    }

    @Test
    @DisplayName("Security: Unconfigured RFC1918 IP is strictly untrusted")
    void shouldRejectUnconfiguredPrivateNetworkIp() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        // Arbitrary peer on corporate / Docker network not in explicit trusted proxies list
        when(req.getRemoteAddr()).thenReturn("10.244.5.12");
        when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.1");

        String resolved = resolver.resolveClientIp(req);
        assertEquals("10.244.5.12", resolved, "Unconfigured RFC1918 addresses must not be trusted as proxies");
    }

    @Test
    @DisplayName("Multi-hop: Right-to-left traversal extracts the first untrusted hop")
    void shouldWalkRightToLeftAndExtractFirstUntrustedHop() {
        // Trusted proxies: 172.20.0.1 (router), 172.20.0.2 (internal gateway)
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("172.20.0.1");
        // Chain: spoofed_client, real_client, trusted_gateway
        when(req.getHeader("X-Forwarded-For")).thenReturn("1.1.1.1, 198.51.100.42, 172.20.0.2");

        String resolved = resolver.resolveClientIp(req);
        assertEquals("198.51.100.42", resolved, 
                "Right-to-left walk must skip trusted 172.20.0.2 and pick the first untrusted hop 198.51.100.42");
    }
}
