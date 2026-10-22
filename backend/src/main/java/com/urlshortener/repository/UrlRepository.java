package com.urlshortener.repository;

import com.urlshortener.entity.Url;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for URL entity.
 * Provides queries for finding URLs by short code and listing user URLs.
 */
@Repository
public interface UrlRepository extends JpaRepository<Url, Long> {
    
    /**
     * Find a URL by its short code.
     * Used during redirect to look up the original URL.
     */
    Optional<Url> findByShortCode(String shortCode);

    /**
     * Find an active (non-deleted) URL by its short code.
     */
    Optional<Url> findByShortCodeAndIsDeletedFalse(String shortCode);
    
    /**
     * Find all URLs for a specific user.
     */
    List<Url> findByUserId(Long userId);

    /**
     * Find all active (non-deleted) URLs for a specific user.
     */
    List<Url> findByUserIdAndIsDeletedFalse(Long userId);
    
    /**
     * Check if a short code already exists.
     */
    boolean existsByShortCode(String shortCode);

    /**
     * Find top 50 most recent active URLs for proactive cache warming.
     */
    List<Url> findTop50ByIsDeletedFalseOrderByCreatedAtDesc();

    /**
     * Legacy cache warming query.
     */
    List<Url> findTop50ByOrderByCreatedAtDesc();
}
