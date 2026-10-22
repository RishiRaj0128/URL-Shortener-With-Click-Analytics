package com.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * DTO for URL shortening requests.
 * 
 * Supports:
 * - Target URL (HTTP/HTTPS only, max 2048 characters)
 * - Optional custom alias
 * - Optional expiration (via explicit timestamp or TTL seconds)
 */
public class ShortenUrlRequest {
    
    @NotBlank(message = "URL is required")
    @Size(max = 2048, message = "URL cannot exceed 2048 characters")
    @Pattern(
        regexp = "^https?://[^\\s/$.?#].[^\\s]*$",
        message = "Please provide a valid URL (must start with http:// or https://)"
    )
    private String url;

    private String customAlias;

    private LocalDateTime expiresAt;

    private Long ttlSeconds;
    
    public ShortenUrlRequest() {}
    
    public ShortenUrlRequest(String url) {
        this.url = url;
    }

    public ShortenUrlRequest(String url, String customAlias) {
        this.url = url;
        this.customAlias = customAlias;
    }

    public ShortenUrlRequest(String url, String customAlias, LocalDateTime expiresAt) {
        this.url = url;
        this.customAlias = customAlias;
        this.expiresAt = expiresAt;
    }

    public ShortenUrlRequest(String url, String customAlias, Long ttlSeconds) {
        this.url = url;
        this.customAlias = customAlias;
        this.ttlSeconds = ttlSeconds;
    }
    
    public String getUrl() {
        return url;
    }
    
    public void setUrl(String url) {
        this.url = url;
    }

    public String getCustomAlias() {
        return customAlias;
    }

    public void setCustomAlias(String customAlias) {
        this.customAlias = customAlias;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(Long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}
