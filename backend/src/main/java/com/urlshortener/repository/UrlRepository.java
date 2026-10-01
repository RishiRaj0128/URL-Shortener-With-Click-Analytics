package com.urlshortener.repository;

import com.urlshortener.entity.Url;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for URL entity.
 * Provides custom queries for finding URLs by short code and listing user URLs.
 */
@Repository
public interface UrlRepository extends JpaRepository<Url, Long> {
    
    /**
     * Find a URL by its short code.
     * Used during redirect to look up the original URL.
     * This query should be fast and is a candidate for Redis caching.
     */
    Optional<Url> findByShortCode(String shortCode);
    
    /**
     * Find all URLs for a specific user.
     * Used in the dashboard to list user's shortened URLs.
     */
    List<Url> findByUserId(Long userId);
    
    /**
     * Check if a short code already exists.
     * Used to handle collisions in short code generation.
     */
    boolean existsByShortCode(String shortCode);

    /**
     * Find top 50 most recent URLs for proactive cache warming.
     */
    List<Url> findTop50ByOrderByCreatedAtDesc();
}
