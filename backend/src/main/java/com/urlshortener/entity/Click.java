package com.urlshortener.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Click entity representing an analytics event for a shortened URL.
 * 
 * Design decisions:
 * - Stores IP as a hash (not raw) for privacy compliance (GDPR, etc.)
 * - Indexed on url_id for quick lookups per shortened URL
 * - Indexed on timestamp for time-range queries (e.g., clicks in last 7 days)
 * - Indexed on country for geographic breakdown queries
 * - No unique constraint; multiple clicks on same URL are expected
 */
@Entity
@Table(name = "clicks", indexes = {
    @Index(name = "idx_clicks_url_id", columnList = "url_id"),
    @Index(name = "idx_clicks_timestamp", columnList = "timestamp"),
    @Index(name = "idx_clicks_country", columnList = "country")
})
public class Click {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "url_id", nullable = false)
  private Url url;

  @Column(name = "timestamp", nullable = false, updatable = false)
  private LocalDateTime timestamp;

  @Column(name = "device_type", length = 50)
  private String deviceType; // e.g., "mobile", "desktop", "tablet"

  @Column(name = "browser", length = 100)
  private String browser; // e.g., "Chrome", "Firefox", "Safari"

  @Column(name = "os", length = 100)
  private String os; // e.g., "Windows", "macOS", "iOS"

  @Column(name = "referrer", columnDefinition = "TEXT")
  private String referrer; // HTTP Referer header

  @Column(name = "country", length = 2)
  private String country; // ISO 3166-1 alpha-2 country code

  @Column(name = "city", length = 100)
  private String city;

  @Column(name = "ip_hash", length = 64)
  private String ipHash; // SHA-256 hash of the client IP

  public Click() {
  }

  public Click(Long id, Url url, LocalDateTime timestamp, String deviceType, String browser, String os, String referrer,
      String country, String city, String ipHash) {
    this.id = id;
    this.url = url;
    this.timestamp = timestamp;
    this.deviceType = deviceType;
    this.browser = browser;
    this.os = os;
    this.referrer = referrer;
    this.country = country;
    this.city = city;
    this.ipHash = ipHash;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Url getUrl() {
    return url;
  }

  public void setUrl(Url url) {
    this.url = url;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }

  public String getDeviceType() {
    return deviceType;
  }

  public void setDeviceType(String deviceType) {
    this.deviceType = deviceType;
  }

  public String getBrowser() {
    return browser;
  }

  public void setBrowser(String browser) {
    this.browser = browser;
  }

  public String getOs() {
    return os;
  }

  public void setOs(String os) {
    this.os = os;
  }

  public String getReferrer() {
    return referrer;
  }

  public void setReferrer(String referrer) {
    this.referrer = referrer;
  }

  public String getCountry() {
    return country;
  }

  public void setCountry(String country) {
    this.country = country;
  }

  public String getCity() {
    return city;
  }

  public void setCity(String city) {
    this.city = city;
  }

  public String getIpHash() {
    return ipHash;
  }

  public void setIpHash(String ipHash) {
    this.ipHash = ipHash;
  }

  public String getOS() {
    return os;
  }

  public void setOS(String os) {
    this.os = os;
  }

  @PrePersist
  protected void onCreate() {
    if (timestamp == null) {
      timestamp = LocalDateTime.now();
    }
  }
}
