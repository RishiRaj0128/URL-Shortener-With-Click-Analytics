package com.urlshortener.exception;

import org.springframework.http.HttpStatus;

/**
 * Base exception class for URL Shortener business logic errors.
 */
public class UrlShortenerException extends RuntimeException {
    
    private final HttpStatus statusCode;
    
    public UrlShortenerException(String message, HttpStatus statusCode) {
        super(message);
        this.statusCode = statusCode;
    }
    
    public UrlShortenerException(String message, HttpStatus statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }
    
    public HttpStatus getStatusCode() {
        return statusCode;
    }
}
