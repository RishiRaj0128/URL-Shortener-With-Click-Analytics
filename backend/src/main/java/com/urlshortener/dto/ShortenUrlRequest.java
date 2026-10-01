package com.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * DTO for URL shortening requests.
 */
public class ShortenUrlRequest {
    
    @NotBlank(message = "URL is required")
    @Pattern(
        regexp = "^(https?|ftp)://[^\\s/$.?#].[^\\s]*$",
        message = "Please provide a valid URL (must start with http://, https://, or ftp://)"
    )
    private String url;
    
    public ShortenUrlRequest() {}
    
    public ShortenUrlRequest(String url) {
        this.url = url;
    }
    
    public String getUrl() {
        return url;
    }
    
    public void setUrl(String url) {
        this.url = url;
    }
}
