package com.urlshortener.util;

import com.urlshortener.exception.UrlShortenerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for UrlSafetyValidator.
 * 
 * Tests:
 * - Scheme restrictions (http/https allowed; ftp, file, javascript, data blocked)
 * - URL length boundaries (max 2048 chars)
 * - SSRF protection (loopback, private subnets, cloud metadata, local domains)
 * - Self-referencing redirect loop protection
 * - Custom alias validation and reserved keyword defense
 */
class UrlSafetyValidatorTest {

    private UrlSafetyValidator validator;

    @BeforeEach
    void setUp() {
        validator = new UrlSafetyValidator("http://localhost:8080");
    }

    @Test
    @DisplayName("Valid public HTTP and HTTPS URLs should pass validation")
    void shouldAcceptValidPublicUrls() {
        assertDoesNotThrow(() -> validator.validateUrl("https://www.google.com"));
        assertDoesNotThrow(() -> validator.validateUrl("https://github.com/torvalds/linux"));
        assertDoesNotThrow(() -> validator.validateUrl("http://example.com:8080/path?query=1#frag"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ftp://ftp.example.com/file.zip",
        "file:///etc/passwd",
        "javascript:alert(1)",
        "data:text/html,hello",
        "mailto:user@example.com",
        "tel:+123456789"
    })
    @DisplayName("Non-HTTP/HTTPS schemes should be rejected")
    void shouldRejectNonHttpSchemes(String url) {
        UrlShortenerException ex = assertThrows(UrlShortenerException.class, () -> validator.validateUrl(url));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("Invalid URL protocol"));
    }

    @Test
    @DisplayName("Empty or null URLs should be rejected")
    void shouldRejectEmptyUrls() {
        assertThrows(UrlShortenerException.class, () -> validator.validateUrl(null));
        assertThrows(UrlShortenerException.class, () -> validator.validateUrl("   "));
    }

    @Test
    @DisplayName("URLs exceeding 2048 characters should be rejected")
    void shouldEnforceMaxUrlLength() {
        String longPath = "a".repeat(2040);
        String validUrl = "https://example.com/" + longPath; // Total 2060 chars > 2048

        UrlShortenerException ex = assertThrows(UrlShortenerException.class, () -> validator.validateUrl(validUrl));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("exceeds maximum allowed length"));

        // Exact boundary: 2048 characters
        String exactBoundary = "https://example.com/" + "a".repeat(2048 - "https://example.com/".length());
        assertEquals(2048, exactBoundary.length());
        assertDoesNotThrow(() -> validator.validateUrl(exactBoundary));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost/secret",
        "http://localhost:3000/api",
        "http://127.0.0.1:8080/admin",
        "http://127.0.0.1/test",
        "http://0.0.0.0:8000/",
        "http://169.254.169.254/latest/meta-data/",
        "http://metadata.google.internal/computeMetadata/v1/",
        "http://service.local/status",
        "http://internal.service.internal/db",
        "http://10.0.0.1:8080/",
        "http://172.16.0.1/",
        "http://192.168.1.1/setup"
    })
    @DisplayName("SSRF targets (loopback, link-local, cloud metadata, private RFC-1918) should be blocked")
    void shouldBlockSsrfTargets(String target) {
        UrlShortenerException ex = assertThrows(UrlShortenerException.class, () -> validator.validateUrl(target));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("SSRF") || ex.getMessage().contains("Prohibited host"));
    }

    @Test
    @DisplayName("Self-referencing redirect loops back to the service base URL must be blocked")
    void shouldBlockSelfReferencingUrls() {
        UrlShortenerException ex = assertThrows(UrlShortenerException.class, () -> 
            validator.validateUrl("http://localhost:8080/loopCode")
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("Custom alias should accept valid alphanumeric, hyphens, and underscores between 3 and 30 chars")
    void shouldValidateCustomAliasSuccess() {
        assertDoesNotThrow(() -> validator.validateCustomAlias("my-promo-link"));
        assertDoesNotThrow(() -> validator.validateCustomAlias("summer_sale_2026"));
        assertDoesNotThrow(() -> validator.validateCustomAlias("abc"));
        assertDoesNotThrow(() -> validator.validateCustomAlias("a".repeat(30)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ab",                    // Too short (< 3)
        "this-alias-is-way-too-long-to-be-allowed-12345", // > 30 chars
        "invalid with spaces",
        "special$char",
        "slash/not/allowed",
        "question?mark"
    })
    @DisplayName("Custom alias with invalid length or characters must be rejected")
    void shouldRejectInvalidCustomAliasFormat(String alias) {
        UrlShortenerException ex = assertThrows(UrlShortenerException.class, () -> validator.validateCustomAlias(alias));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("Custom alias must be between 3 and 30 characters"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "api", "auth", "login", "signup", "admin", "health", "metrics",
        "actuator", "shorten", "urls", "analytics", "swagger", "docs"
    })
    @DisplayName("Custom alias colliding with reserved endpoints must be rejected")
    void shouldRejectReservedKeywordsAsCustomAlias(String reserved) {
        UrlShortenerException ex = assertThrows(UrlShortenerException.class, () -> validator.validateCustomAlias(reserved));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("reserved keyword"));
    }
}
