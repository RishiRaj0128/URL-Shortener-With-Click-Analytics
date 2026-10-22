package com.urlshortener.service;

import com.urlshortener.dto.ShortenUrlRequest;
import com.urlshortener.dto.ShortenUrlResponse;
import com.urlshortener.dto.UrlResponse;
import com.urlshortener.entity.Url;
import com.urlshortener.entity.User;
import com.urlshortener.exception.UrlShortenerException;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.repository.UserRepository;
import com.urlshortener.util.Base62Encoder;
import com.urlshortener.util.QrCodeGenerator;
import com.urlshortener.util.SnowflakeIdGenerator;
import com.urlshortener.util.UrlSafetyValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for URL shortening operations.
 * 
 * Responsibilities:
 * - Validate target URLs for SSRF safety, protocol restrictions, and length
 * - Generate distributed-safe 64-bit Snowflake short codes or reserve custom aliases
 * - Handle link expiration (TTL) and soft delete
 * - Store shortened URLs in a single write operation
 * - Look up shortened URLs (with Redis caching and eviction)
 * - Generate QR codes
 */
@Service
@Transactional
public class UrlShorteningService {
    
    @Value("${server.servlet.context-path:}")
    private String contextPath;
    
    @Value("${server.port:8080}")
    private String serverPort;
    
    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;
    
    private final UrlRepository urlRepository;
    private final UserRepository userRepository;
    private final QrCodeGenerator qrCodeGenerator;
    private final ClickService clickService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final UrlSafetyValidator urlSafetyValidator;
    
    @Autowired
    public UrlShorteningService(
            UrlRepository urlRepository,
            UserRepository userRepository,
            QrCodeGenerator qrCodeGenerator,
            ClickService clickService,
            SnowflakeIdGenerator snowflakeIdGenerator,
            @Autowired(required = false) UrlSafetyValidator urlSafetyValidator) {
        this.urlRepository = urlRepository;
        this.userRepository = userRepository;
        this.qrCodeGenerator = qrCodeGenerator;
        this.clickService = clickService;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.urlSafetyValidator = urlSafetyValidator != null ? urlSafetyValidator : new UrlSafetyValidator();
    }

    /**
     * Legacy constructor for backwards-compatibility in unit tests.
     */
    public UrlShorteningService(
            UrlRepository urlRepository,
            UserRepository userRepository,
            QrCodeGenerator qrCodeGenerator,
            ClickService clickService,
            SnowflakeIdGenerator snowflakeIdGenerator) {
        this(urlRepository, userRepository, qrCodeGenerator, clickService, snowflakeIdGenerator, new UrlSafetyValidator());
    }
    
    /**
     * Create a shortened URL using zero-coordination Snowflake ID generation or custom alias.
     * Enforces SSRF safety, max length, protocol allowlist, and optional TTL.
     */
    public ShortenUrlResponse shortenUrl(ShortenUrlRequest request, String userEmail) {
        User user = userRepository.findByEmail(userEmail)
            .orElseThrow(() -> new UrlShortenerException(
                "User not found",
                HttpStatus.NOT_FOUND
            ));
        
        // Comprehensive SSRF and safety validation
        String rawUrl = request.getUrl();
        urlSafetyValidator.validateUrl(rawUrl);

        String normalizedUrl = rawUrl.trim();
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            normalizedUrl = "https://" + normalizedUrl;
        }

        // Determine short code: custom alias or Snowflake Base62
        String shortCode;
        if (request.getCustomAlias() != null && !request.getCustomAlias().trim().isEmpty()) {
            String alias = request.getCustomAlias().trim();
            urlSafetyValidator.validateCustomAlias(alias);
            if (urlRepository.existsByShortCode(alias)) {
                throw new UrlShortenerException(
                    "Custom alias '" + alias + "' is already in use",
                    HttpStatus.CONFLICT
                );
            }
            shortCode = alias;
        } else {
            long snowflakeId = snowflakeIdGenerator.nextId();
            shortCode = Base62Encoder.encode(snowflakeId);
        }

        // Expiration calculation
        LocalDateTime expiresAt = request.getExpiresAt();
        if (expiresAt == null && request.getTtlSeconds() != null && request.getTtlSeconds() > 0) {
            expiresAt = LocalDateTime.now().plusSeconds(request.getTtlSeconds());
        }
        if (expiresAt != null && expiresAt.isBefore(LocalDateTime.now())) {
            throw new UrlShortenerException(
                "Expiration time cannot be in the past",
                HttpStatus.BAD_REQUEST
            );
        }

        Url url = new Url();
        url.setUser(user);
        url.setOriginalUrl(normalizedUrl);
        url.setShortCode(shortCode);
        url.setExpiresAt(expiresAt);
        url.setDeleted(false);
        url.setCreatedAt(LocalDateTime.now());
        url.setUpdatedAt(LocalDateTime.now());
        
        // Single atomic persistence operation
        urlRepository.save(url);
        
        // Generate QR code for the short URL
        String shortUrl = buildShortUrl(shortCode);
        String qrCode = generateQrCode(shortUrl);
        
        return new ShortenUrlResponse(
            shortCode,
            shortUrl,
            normalizedUrl,
            qrCode,
            expiresAt
        );
    }
    
    /**
     * Look up original URL by short code.
     * Uses @Cacheable to cache the result in Redis.
     * 
     * Cache-Aside Pattern:
     * 1. Check Redis (via @Cacheable)
     * 2. Cache hit → return immediately
     * 3. Cache miss → query database, populate cache
     */
    @Cacheable(value = "urls", key = "#shortCode")
    @Transactional(readOnly = true)
    public Url getUrlByShortCode(String shortCode) {
        Url url = urlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new UrlShortenerException(
                "Short URL not found: " + shortCode,
                HttpStatus.NOT_FOUND
            ));

        if (url.isDeleted()) {
            throw new UrlShortenerException(
                "Short URL has been deleted: " + shortCode,
                HttpStatus.GONE
            );
        }

        if (url.isExpired()) {
            throw new UrlShortenerException(
                "Short URL has expired: " + shortCode,
                HttpStatus.GONE
            );
        }

        return url;
    }

    /**
     * Explicitly evicts a short code from Redis cache.
     */
    @CacheEvict(value = "urls", key = "#shortCode")
    public void evictCache(String shortCode) {
        // Cache eviction hook
    }
    
    /**
     * Get all active (non-deleted) URLs for a user.
     */
    public List<UrlResponse> getUserUrls(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
            .orElseThrow(() -> new UrlShortenerException(
                "User not found",
                HttpStatus.NOT_FOUND
            ));
        
        List<Url> urls = urlRepository.findByUserIdAndIsDeletedFalse(user.getId());
        
        return urls.stream()
            .map(url -> {
                long clickCount = clickService.getClickCount(url.getId());
                return new UrlResponse(
                    url.getId(),
                    url.getShortCode(),
                    buildShortUrl(url.getShortCode()),
                    url.getOriginalUrl(),
                    url.getCreatedAt(),
                    clickCount,
                    url.getExpiresAt(),
                    url.isExpired()
                );
            })
            .collect(Collectors.toList());
    }
    
    /**
     * Soft-deletes a shortened URL.
     * Evicts from cache immediately and marks as deleted in database.
     */
    @CacheEvict(value = "urls", key = "#shortCode")
    public void deleteUrl(String shortCode, String userEmail) {
        Url url = urlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new UrlShortenerException(
                "Short URL not found: " + shortCode,
                HttpStatus.NOT_FOUND
            ));
        
        User user = userRepository.findByEmail(userEmail)
            .orElseThrow(() -> new UrlShortenerException(
                "User not found",
                HttpStatus.NOT_FOUND
            ));
        
        // Ensure user owns this URL
        if (!url.getUser().getId().equals(user.getId())) {
            throw new UrlShortenerException(
                "Unauthorized to delete this URL",
                HttpStatus.FORBIDDEN
            );
        }
        
        url.setDeleted(true);
        url.setDeletedAt(LocalDateTime.now());
        urlRepository.save(url);
    }
    
    /**
     * Update target URL for an existing short code.
     * Validates new URL safety and evicts Redis cache-aside entry immediately.
     */
    @CacheEvict(value = "urls", key = "#shortCode")
    public UrlResponse updateUrl(String shortCode, String newOriginalUrl, String userEmail) {
        Url url = urlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new UrlShortenerException(
                "Short URL not found: " + shortCode,
                HttpStatus.NOT_FOUND
            ));
        
        User user = userRepository.findByEmail(userEmail)
            .orElseThrow(() -> new UrlShortenerException(
                "User not found",
                HttpStatus.NOT_FOUND
            ));
        
        if (!url.getUser().getId().equals(user.getId())) {
            throw new UrlShortenerException(
                "Unauthorized to update this URL",
                HttpStatus.FORBIDDEN
            );
        }

        urlSafetyValidator.validateUrl(newOriginalUrl);

        String normalizedUrl = newOriginalUrl.trim();
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            normalizedUrl = "https://" + normalizedUrl;
        }
        
        url.setOriginalUrl(normalizedUrl);
        url.setUpdatedAt(LocalDateTime.now());
        Url updated = urlRepository.save(url);
        
        long clickCount = clickService.getClickCount(updated.getId());
        return new UrlResponse(
            updated.getId(),
            updated.getShortCode(),
            buildShortUrl(updated.getShortCode()),
            updated.getOriginalUrl(),
            updated.getCreatedAt(),
            clickCount,
            updated.getExpiresAt(),
            updated.isExpired()
        );
    }
    
    /**
     * Build the short URL from short code.
     * Example: "http://localhost:8080/abc123"
     */
    private String buildShortUrl(String shortCode) {
        String effectiveBase = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : "http://localhost:8080";
        String base = effectiveBase.endsWith("/") ? effectiveBase.substring(0, effectiveBase.length() - 1) : effectiveBase;
        return base + "/" + shortCode;
    }
    
    /**
     * Generate QR code for a URL.
     */
    private String generateQrCode(String url) {
        try {
            return qrCodeGenerator.generateQrCode(url);
        } catch (Exception e) {
            throw new UrlShortenerException(
                "Failed to generate QR code",
                HttpStatus.INTERNAL_SERVER_ERROR,
                e
            );
        }
    }
}
