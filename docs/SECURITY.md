# Security Architecture & Phishing Mitigation Guide

## 1. Overview & Threat Model

URL shorteners are high-value targets for malicious actors. Because shortened URLs mask destination targets behind a trusted domain, they are frequently exploited for:
- **Phishing & Credential Harvesting**: Directing unsuspecting users to credential phishing pages using an otherwise reputable URL shortener domain.
- **Open Redirect Exploits**: Bypassing corporate security filters, email gateways, and social network link inspectors.
- **Server-Side Request Forgery (SSRF)**: Triggering requests against private backend infrastructure, container networks, or cloud metadata endpoints (`169.254.169.254`).
- **Denial of Service (DoS)**: Submitting massive strings to exhaust memory/CPU buffers or creating self-referencing redirect loops.

This document outlines the multi-layered security controls implemented to eliminate these attack vectors.

---

## 2. Implemented Defense-in-Depth Controls

### A. Strict Scheme Allowlist
- **Rule**: Target URLs must strictly use `http://` or `https://`.
- **Enforcement**: Evaluated via both Bean Validation regex (`^https?://...`) on [`ShortenUrlRequest`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/dto/ShortenUrlRequest.java) and programmatic URI inspection in [`UrlSafetyValidator`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/util/UrlSafetyValidator.java).
- **Blocked**: Schemes such as `javascript:`, `data:`, `file:`, `ftp:`, `mailto:`, and `tel:` are rejected with `HTTP 400 Bad Request`.

### B. Maximum URL Length Bound
- **Rule**: URLs are bounded to a maximum length of **2048 characters** (RFC 2616 / modern browser maximum URI length).
- **Enforcement**: [`UrlSafetyValidator.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/util/UrlSafetyValidator.java) rejects any string exceeding 2048 characters before parsing or database persistence.

### C. SSRF & Cloud Metadata Protection
- **Rule**: Shortened links may never resolve to private infrastructure, loopback interfaces, or cloud metadata endpoints.
- **Enforcement**:
  1. **Hostname Blacklist**: Directly blocks `localhost`, `127.0.0.1`, `::1`, `0.0.0.0`, `169.254.169.254`, `metadata.google.internal`, and domains ending in `.localhost`, `.local`, `.internal`, or `.arpa`.
  2. **IP Resolution Checks**: Resolves target hostnames (`InetAddress.getAllByName`) and inspects the binary octets:
     - **Loopback**: `127.0.0.0/8`, `::1`
     - **Private RFC-1918**: `10.0.0.0/8`, `172.16.0.0/12` (`172.16.0.0`–`172.31.255.255`), `192.168.0.0/16`
     - **Link-Local & Cloud Metadata**: `169.254.0.0/16`, `169.254.169.254`, `fe80::/10`
     - **Carrier-Grade NAT**: `100.64.0.0/10`
     - **IPv6 Unique Local**: `fc00::/7`
- Any target resolving to a prohibited address is rejected with `HTTP 400 Bad Request`.

### D. Self-Referencing Redirect Loop Defense
- **Rule**: A short link cannot redirect to the URL shortener instance itself.
- **Enforcement**: [`UrlSafetyValidator`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/util/UrlSafetyValidator.java) compares the target host and port against `app.base-url`. Matches are rejected to prevent amplification loops.

### E. Custom Alias Boundaries & Reserved Keywords
- **Format**: Restricted strictly to `^[a-zA-Z0-9_-]{3,30}$`.
- **Reserved Keywords**: Aliases colliding with system, authentication, or observability routes are prohibited:
  - `api`, `auth`, `login`, `signup`, `register`, `logout`
  - `admin`, `health`, `ready`, `metrics`, `actuator`, `prometheus`
  - `system`, `swagger`, `swagger-ui`, `v3`, `docs`, `chaos`
  - `urls`, `analytics`, `shorten`, `favicon.ico`, `robots.txt`, `static`
- Collisions with existing codes return `HTTP 409 Conflict`.

### F. Link Expiration & Soft Deletion Semantics
- **TTL Support**: Links can be created with explicit `expiresAt` timestamps or `ttlSeconds`.
- **Soft Delete**: User deletions mark `is_deleted = true` and log `deleted_at`, preserving click history for auditing without allowing further public redirects.
- **Cache Eviction**: Deleting a link immediately purges its entry from Redis (`@CacheEvict(value = "urls", key = "#shortCode")`).
- **Fast-Path Safety**: If an expired or deleted link is accessed, [`RedirectController`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/controller/RedirectController.java) immediately evicts any lingering cache entry and returns `HTTP 410 Gone`.

### G. Cryptographic IP Anonymization (HMAC-SHA256)
- Raw IP addresses are **never** stored in the database.
- Click events are hashed using `HmacSHA256` with a server-side secret (`IP_HASH_SECRET`) and a rotatable salt (`IP_HASH_SALT`), eliminating precomputed rainbow table attacks across the 32-bit IPv4 address space while maintaining GDPR compliance.

### H. Authentication & Password Security
- **JWT Key Strength**: [`JwtConfiguration`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/config/JwtConfiguration.java) validates on application startup that `app.jwt.secret` is at least 256 bits (32 bytes) and rejects insecure defaults (`secret`, `changeme`, etc.).
- **Password Hashing**: [`SecurityConfiguration`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/config/SecurityConfiguration.java) explicitly sets the BCrypt work factor to **12 rounds**, resisting brute-force offline cracking.

---

## 3. Operational Takedown & Phishing Workflow

If an abusive or phishing URL is reported:
1. **Immediate API Invalidation**: Calling `DELETE /api/urls/{shortCode}` immediately marks the link as deleted in PostgreSQL and triggers instant Redis cache eviction.
2. **Fast-Path Rejection**: Subsequent redirect requests receive `HTTP 410 Gone`.
3. **Forensic Audit**: The `clicks` and `click_event_dedup` tables retain hashed IP audit trails to measure how many users were targeted prior to takedown without violating user privacy.
