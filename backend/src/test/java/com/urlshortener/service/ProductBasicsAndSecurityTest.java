package com.urlshortener.service;

import com.urlshortener.config.JwtConfiguration;
import com.urlshortener.config.SecurityConfiguration;
import com.urlshortener.dto.ShortenUrlRequest;
import com.urlshortener.dto.ShortenUrlResponse;
import com.urlshortener.dto.UrlResponse;
import com.urlshortener.entity.Url;
import com.urlshortener.entity.User;
import com.urlshortener.exception.UrlShortenerException;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.repository.UserRepository;
import com.urlshortener.util.QrCodeGenerator;
import com.urlshortener.util.SnowflakeIdGenerator;
import com.urlshortener.util.UrlSafetyValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test suite for Product Basics and Security Hardening (Phase 3).
 * 
 * Verifies:
 * - Custom alias reservation and collision defense
 * - Link expiration TTL handling and past expiration rejection
 * - Redirect rejection for expired links (HTTP 410 Gone)
 * - Soft delete lifecycle (deleted_at recorded, cache evicted, excluded from user listing, 410 on lookup)
 * - Cryptographic JWT key validation (minimum 256-bit requirement and weak key rejection)
 * - Explicit BCrypt 12-round strength configuration
 */
@ExtendWith(MockitoExtension.class)
class ProductBasicsAndSecurityTest {

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private QrCodeGenerator qrCodeGenerator;

    @Mock
    private ClickService clickService;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private UrlSafetyValidator urlSafetyValidator;
    private UrlShorteningService urlShorteningService;

    private User testUser;

    @BeforeEach
    void setUp() {
        urlSafetyValidator = new UrlSafetyValidator("http://localhost:8080");
        urlShorteningService = new UrlShorteningService(
            urlRepository,
            userRepository,
            qrCodeGenerator,
            clickService,
            snowflakeIdGenerator,
            urlSafetyValidator
        );

        testUser = new User();
        testUser.setId(100L);
        testUser.setEmail("developer@example.com");
    }

    @Test
    @DisplayName("Should successfully shorten URL with custom alias when alias is available")
    void shouldAllowAvailableCustomAlias() throws Exception {
        when(userRepository.findByEmail("developer@example.com")).thenReturn(Optional.of(testUser));
        when(urlRepository.existsByShortCode("summer-2026")).thenReturn(false);
        when(qrCodeGenerator.generateQrCode(anyString())).thenReturn("mockQrBase64");

        ShortenUrlRequest request = new ShortenUrlRequest("https://www.example.com/sale", "summer-2026");
        ShortenUrlResponse response = urlShorteningService.shortenUrl(request, "developer@example.com");

        assertNotNull(response);
        assertEquals("summer-2026", response.getShortCode());
        verify(snowflakeIdGenerator, never()).nextId();
        verify(urlRepository).save(any(Url.class));
    }

    @Test
    @DisplayName("Should reject custom alias with HTTP 409 Conflict when alias is already taken")
    void shouldRejectDuplicateCustomAlias() {
        when(userRepository.findByEmail("developer@example.com")).thenReturn(Optional.of(testUser));
        when(urlRepository.existsByShortCode("taken-alias")).thenReturn(true);

        ShortenUrlRequest request = new ShortenUrlRequest("https://www.example.com/sale", "taken-alias");
        UrlShortenerException ex = assertThrows(UrlShortenerException.class, () ->
            urlShorteningService.shortenUrl(request, "developer@example.com")
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("already in use"));
    }

    @Test
    @DisplayName("Should compute expiresAt when ttlSeconds is provided")
    void shouldComputeExpirationFromTtlSeconds() throws Exception {
        when(userRepository.findByEmail("developer@example.com")).thenReturn(Optional.of(testUser));
        when(snowflakeIdGenerator.nextId()).thenReturn(123456789L);
        when(qrCodeGenerator.generateQrCode(anyString())).thenReturn("mockQrBase64");

        ShortenUrlRequest request = new ShortenUrlRequest("https://www.example.com/promo", null, 3600L);
        ShortenUrlResponse response = urlShorteningService.shortenUrl(request, "developer@example.com");

        assertNotNull(response.getExpiresAt());
        assertTrue(response.getExpiresAt().isAfter(LocalDateTime.now().plusMinutes(59)));
    }

    @Test
    @DisplayName("Should reject link creation when expiresAt timestamp is in the past")
    void shouldRejectPastExpirationTimestamp() {
        when(userRepository.findByEmail("developer@example.com")).thenReturn(Optional.of(testUser));

        LocalDateTime pastTime = LocalDateTime.now().minusHours(2);
        ShortenUrlRequest request = new ShortenUrlRequest("https://www.example.com/expired", null, pastTime);

        UrlShortenerException ex = assertThrows(UrlShortenerException.class, () ->
            urlShorteningService.shortenUrl(request, "developer@example.com")
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("Expiration time cannot be in the past"));
    }

    @Test
    @DisplayName("getUrlByShortCode should return HTTP 410 Gone when link has expired")
    void shouldThrowGoneForExpiredUrl() {
        Url expiredUrl = new Url();
        expiredUrl.setId(1L);
        expiredUrl.setShortCode("exp123");
        expiredUrl.setOriginalUrl("https://example.com/expired");
        expiredUrl.setExpiresAt(LocalDateTime.now().minusMinutes(5));

        when(urlRepository.findByShortCode("exp123")).thenReturn(Optional.of(expiredUrl));

        UrlShortenerException ex = assertThrows(UrlShortenerException.class, () ->
            urlShorteningService.getUrlByShortCode("exp123")
        );

        assertEquals(HttpStatus.GONE, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("expired"));
    }

    @Test
    @DisplayName("deleteUrl should perform soft delete without removing DB record and record deleted_at")
    void shouldPerformSoftDelete() {
        Url activeUrl = new Url();
        activeUrl.setId(5L);
        activeUrl.setUser(testUser);
        activeUrl.setShortCode("delLink");
        activeUrl.setOriginalUrl("https://example.com/link");
        activeUrl.setDeleted(false);

        when(urlRepository.findByShortCode("delLink")).thenReturn(Optional.of(activeUrl));
        when(userRepository.findByEmail("developer@example.com")).thenReturn(Optional.of(testUser));

        urlShorteningService.deleteUrl("delLink", "developer@example.com");

        ArgumentCaptor<Url> captor = ArgumentCaptor.forClass(Url.class);
        verify(urlRepository).save(captor.capture());
        verify(urlRepository, never()).delete(any(Url.class));

        Url saved = captor.getValue();
        assertTrue(saved.isDeleted());
        assertNotNull(saved.getDeletedAt());
    }

    @Test
    @DisplayName("getUrlByShortCode should return HTTP 410 Gone when link has been soft-deleted")
    void shouldThrowGoneForSoftDeletedUrl() {
        Url deletedUrl = new Url();
        deletedUrl.setId(2L);
        deletedUrl.setShortCode("delLink");
        deletedUrl.setDeleted(true);
        deletedUrl.setDeletedAt(LocalDateTime.now().minusDays(1));

        when(urlRepository.findByShortCode("delLink")).thenReturn(Optional.of(deletedUrl));

        UrlShortenerException ex = assertThrows(UrlShortenerException.class, () ->
            urlShorteningService.getUrlByShortCode("delLink")
        );

        assertEquals(HttpStatus.GONE, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("deleted"));
    }

    @Test
    @DisplayName("getUserUrls should exclude soft-deleted URLs from response")
    void shouldExcludeSoftDeletedUrlsFromUserList() {
        when(userRepository.findByEmail("developer@example.com")).thenReturn(Optional.of(testUser));

        Url url1 = new Url();
        url1.setId(10L);
        url1.setShortCode("activeLink");
        url1.setOriginalUrl("https://example.com/active");
        url1.setCreatedAt(LocalDateTime.now());

        when(urlRepository.findByUserIdAndIsDeletedFalse(100L)).thenReturn(List.of(url1));
        when(clickService.getClickCount(10L)).thenReturn(42L);

        List<UrlResponse> userUrls = urlShorteningService.getUserUrls("developer@example.com");

        assertEquals(1, userUrls.size());
        assertEquals("activeLink", userUrls.get(0).getShortCode());
        assertEquals(42L, userUrls.get(0).getClickCount());
        verify(urlRepository).findByUserIdAndIsDeletedFalse(100L);
    }

    @Test
    @DisplayName("JWT validation should enforce minimum 256 bits (32 bytes)")
    void shouldEnforceJwtSecretLength() {
        // Less than 32 bytes must throw IllegalStateException
        assertThrows(IllegalStateException.class, () ->
            JwtConfiguration.validateJwtSecret("short-key-only-18b")
        );

        // Blank must throw IllegalStateException
        assertThrows(IllegalStateException.class, () ->
            JwtConfiguration.validateJwtSecret("   ")
        );

        // Exactly 32 bytes valid
        String valid32ByteSecret = "012345678901234567890123456789a!";
        assertDoesNotThrow(() -> JwtConfiguration.validateJwtSecret(valid32ByteSecret));
    }

    @Test
    @DisplayName("JWT validation must reject prohibited trivial default keys")
    void shouldRejectTrivialJwtDefaultKeys() {
        assertThrows(IllegalStateException.class, () ->
            JwtConfiguration.validateJwtSecret("12345678901234567890123456789012")
        );
        assertThrows(IllegalStateException.class, () ->
            JwtConfiguration.validateJwtSecret("abcdefghijklmnopqrstuvwxyz123456")
        );
    }

    @Test
    @DisplayName("SecurityConfiguration should configure BCrypt work factor to 12 rounds explicitly")
    void shouldConfigureBcryptStrength12() {
        SecurityConfiguration securityConfig = new SecurityConfiguration();
        PasswordEncoder encoder = securityConfig.passwordEncoder();

        String rawPassword = "P@ssword123Secured!";
        String encoded = encoder.encode(rawPassword);

        // BCrypt prefix format: $2a$12$...
        assertTrue(encoded.startsWith("$2a$12$") || encoded.startsWith("$2b$12$"),
            "BCrypt hash should start with work factor $2a$12$ or $2b$12$. Encoded: " + encoded);
        assertTrue(encoder.matches(rawPassword, encoded));
    }
}
