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
 * - Generate distributed-safe 64-bit Snowflake short codes for URLs
 * - Store shortened URLs in a single write operation
 * - Look up shortened URLs (with caching)
 * - Generate QR codes
 * 
 * Cache strategy: @Cacheable on short-code lookups
 * On cache miss, queries database and populates cache.
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
    
    @Autowired
    public UrlShorteningService(
            UrlRepository urlRepository,
            UserRepository userRepository,
            QrCodeGenerator qrCodeGenerator,
            ClickService clickService,
            SnowflakeIdGenerator snowflakeIdGenerator) {
        this.urlRepository = urlRepository;
        this.userRepository = userRepository;
        this.qrCodeGenerator = qrCodeGenerator;
        this.clickService = clickService;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }
    
    /**
     * Create a shortened URL using zero-coordination Snowflake ID generation.
     * Single DB write, eliminating DB roundtrips.
     */
    public ShortenUrlResponse shortenUrl(ShortenUrlRequest request, String userEmail) {
        User user = userRepository.findByEmail(userEmail)
            .orElseThrow(() -> new UrlShortenerException(
                "User not found",
                HttpStatus.NOT_FOUND
            ));
        
        // Generate distributed-safe 64-bit Snowflake ID and pure Base62 code
        long snowflakeId = snowflakeIdGenerator.nextId();
        String shortCode = Base62Encoder.encode(snowflakeId);
        
        String normalizedUrl = request.getUrl().trim();
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://") && !normalizedUrl.startsWith("ftp://")) {
            normalizedUrl = "https://" + normalizedUrl;
        }

        Url url = new Url();
        url.setUser(user);
        url.setOriginalUrl(normalizedUrl);
        url.setShortCode(shortCode);
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
            request.getUrl(),
            qrCode
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
        return urlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new UrlShortenerException(
                "Short URL not found: " + shortCode,
                HttpStatus.NOT_FOUND
            ));
    }
    
    /**
     * Get all URLs for a user.
     */
    public List<UrlResponse> getUserUrls(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
            .orElseThrow(() -> new UrlShortenerException(
                "User not found",
                HttpStatus.NOT_FOUND
            ));
        
        List<Url> urls = urlRepository.findByUserId(user.getId());
        
        return urls.stream()
            .map(url -> {
                long clickCount = clickService.getClickCount(url.getId());
                return new UrlResponse(
                    url.getId(),
                    url.getShortCode(),
                    buildShortUrl(url.getShortCode()),
                    url.getOriginalUrl(),
                    url.getCreatedAt(),
                    clickCount
                );
            })
            .collect(Collectors.toList());
    }
    
    /**
     * Delete a shortened URL.
     * Evicts from cache and removes from database.
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
        
        urlRepository.delete(url);
    }
    
    /**
     * Update target URL for an existing short code.
     * Crucial Cache-Aside Invalidation: Evicts Redis entry immediately on write
     * so subsequent redirects read the updated destination instead of waiting for TTL expiry.
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
        
        url.setOriginalUrl(newOriginalUrl);
        url.setUpdatedAt(LocalDateTime.now());
        Url updated = urlRepository.save(url);
        
        long clickCount = clickService.getClickCount(updated.getId());
        return new UrlResponse(
            updated.getId(),
            updated.getShortCode(),
            buildShortUrl(updated.getShortCode()),
            updated.getOriginalUrl(),
            updated.getCreatedAt(),
            clickCount
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
