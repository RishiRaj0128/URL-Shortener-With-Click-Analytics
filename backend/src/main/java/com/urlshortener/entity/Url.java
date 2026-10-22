package com.urlshortener.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * URL entity representing a shortened URL and its mapping to the original URL.
 * 
 * Design decisions:
 * - short_code is UNIQUE to enable fast lookups and ensure no collisions
 * - Indexed on short_code (for redirects), user_id (for user's URL list),
 *   expires_at (for TTL/cleanup), and is_deleted (for soft delete filtering)
 * - Foreign key constraint with CASCADE delete ensures referential integrity
 */
@Entity
@Table(name = "urls", indexes = {
    @Index(name = "idx_urls_short_code", columnList = "short_code", unique = true),
    @Index(name = "idx_urls_user_id", columnList = "user_id"),
    @Index(name = "idx_urls_expires_at", columnList = "expires_at"),
    @Index(name = "idx_urls_is_deleted", columnList = "is_deleted")
})
public class Url {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  @com.fasterxml.jackson.annotation.JsonIgnore
  private User user;

  @Column(name = "original_url", nullable = false, columnDefinition = "TEXT")
  private String originalUrl;

  @Column(name = "short_code", unique = true, length = 30)
  private String shortCode;

  @Column(name = "expires_at")
  private LocalDateTime expiresAt;

  @Column(name = "is_deleted", nullable = false)
  private boolean isDeleted = false;

  @Column(name = "deleted_at")
  private LocalDateTime deletedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  public Url() {
  }

  public Url(Long id, User user, String originalUrl, String shortCode, LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.id = id;
    this.user = user;
    this.originalUrl = originalUrl;
    this.shortCode = shortCode;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.isDeleted = false;
  }

  public Url(Long id, User user, String originalUrl, String shortCode, LocalDateTime expiresAt,
      LocalDateTime createdAt, LocalDateTime updatedAt) {
    this.id = id;
    this.user = user;
    this.originalUrl = originalUrl;
    this.shortCode = shortCode;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.isDeleted = false;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public User getUser() {
    return user;
  }

  public void setUser(User user) {
    this.user = user;
  }

  public String getOriginalUrl() {
    return originalUrl;
  }

  public void setOriginalUrl(String originalUrl) {
    this.originalUrl = originalUrl;
  }

  public String getShortCode() {
    return shortCode;
  }

  public void setShortCode(String shortCode) {
    this.shortCode = shortCode;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(LocalDateTime expiresAt) {
    this.expiresAt = expiresAt;
  }

  public boolean isDeleted() {
    return isDeleted;
  }

  public void setDeleted(boolean deleted) {
    isDeleted = deleted;
  }

  public LocalDateTime getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(LocalDateTime deletedAt) {
    this.deletedAt = deletedAt;
  }

  public boolean isExpired() {
    return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = LocalDateTime.now();
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
