package com.urlshortener.dto;

import java.time.LocalDateTime;

/**
 * DTO for URL shortening responses.
 */
public class ShortenUrlResponse {
    private String shortCode;
    private String shortUrl;
    private String originalUrl;
    private String qrCodeBase64;  // Base64-encoded PNG image
    private LocalDateTime expiresAt;
    
    public ShortenUrlResponse() {}
    
    public ShortenUrlResponse(String shortCode, String shortUrl, String originalUrl, String qrCodeBase64) {
        this.shortCode = shortCode;
        this.shortUrl = shortUrl;
        this.originalUrl = originalUrl;
        this.qrCodeBase64 = qrCodeBase64;
    }

    public ShortenUrlResponse(String shortCode, String shortUrl, String originalUrl, String qrCodeBase64, LocalDateTime expiresAt) {
        this.shortCode = shortCode;
        this.shortUrl = shortUrl;
        this.originalUrl = originalUrl;
        this.qrCodeBase64 = qrCodeBase64;
        this.expiresAt = expiresAt;
    }
    
    public String getShortCode() {
        return shortCode;
    }
    
    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }
    
    public String getShortUrl() {
        return shortUrl;
    }
    
    public void setShortUrl(String shortUrl) {
        this.shortUrl = shortUrl;
    }
    
    public String getOriginalUrl() {
        return originalUrl;
    }
    
    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }
    
    public String getQrCodeBase64() {
        return qrCodeBase64;
    }
    
    public void setQrCodeBase64(String qrCodeBase64) {
        this.qrCodeBase64 = qrCodeBase64;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
