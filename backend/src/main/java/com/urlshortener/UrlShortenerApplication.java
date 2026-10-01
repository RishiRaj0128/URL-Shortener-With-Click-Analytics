package com.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Main Spring Boot application class for URL Shortener Service.
 * 
 * Enables:
 * - @EnableCaching: Spring's cache abstraction with Redis backend
 * - @EnableAsync: Asynchronous task execution for non-blocking click logging
 */
@SpringBootApplication
@EnableCaching
@EnableAsync
public class UrlShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
