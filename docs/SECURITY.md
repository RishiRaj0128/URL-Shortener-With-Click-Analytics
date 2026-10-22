# Security Policy & Threat Model

This document outlines the security controls, validation rules, threat mitigations, and known architectural limitations implemented in the **URL Shortener with Click Analytics & Traffic Intelligence** service.

---

## 1. Threat Model & Attack Vectors

As a public-facing URL redirection engine, the system is exposed to three primary threat surfaces:
1. **Server-Side Request Forgery (SSRF) & Metadata Scraping**: Malicious actors submitting short URLs pointing to internal networks (`10.0.0.0/8`, `192.168.0.0/16`, `127.0.0.1`) or cloud instance metadata services (`169.254.169.254`) to probe internal infrastructure.
2. **Denial of Service via Amplification & Redirect Loops**: Submitting URLs pointing back to the service itself, creating recursive redirect loops that consume server socket buffers and browser memory.
3. **Phishing, Obfuscation & Rainbow Table IP Inversion**: Shortening links pointing to malicious or executable schemes (`javascript:`, `data:`, `file:///`), or inverting visitor IP hashes to deanonymize users.

---

## 2. Implemented Defense-in-Depth Controls

### A. Strict Scheme Allowlist
- **Rule**: Target URLs must strictly use `http://` or `https://`.
- **Enforcement**: Evaluated via both Bean Validation regex (`^https?://...`) on [`ShortenUrlRequest`](../backend/src/main/java/com/urlshortener/dto/ShortenUrlRequest.java) and programmatic URI inspection in [`UrlSafetyValidator`](../backend/src/main/java/com/urlshortener/util/UrlSafetyValidator.java).
- **Blocked**: Schemes such as `javascript:`, `data:`, `file:`, `ftp:`, `mailto:`, and `tel:` are rejected with `HTTP 400 Bad Request`.

### B. Maximum URL Length Bound
- **Rule**: URLs are bounded to a maximum length of **2048 characters** (RFC 2616 / modern browser maximum URI length).
- **Enforcement**: [`UrlSafetyValidator.java`](../backend/src/main/java/com/urlshortener/util/UrlSafetyValidator.java) rejects any string exceeding 2048 characters before parsing or database persistence.

### C. SSRF & Cloud Metadata Protection
- **Rule**: Shortened links may never resolve to private infrastructure, loopback interfaces, or cloud metadata endpoints.
- **Enforcement**:
  1. **Hostname Denylist**: Directly blocks `localhost`, `127.0.0.1`, `::1`, `0.0.0.0`, `169.254.169.254`, `metadata.google.internal`, and domains ending in `.localhost`, `.local`, `.internal`, or `.arpa`.
  2. **IP Resolution Checks**: Resolves target hostnames (`InetAddress.getAllByName`) and inspects the binary octets:
     - **Loopback**: `127.0.0.0/8`, `::1`
     - **Private RFC-1918**: `10.0.0.0/8`, `172.16.0.0/12` (`172.16.0.0`–`172.31.255.255`), `192.168.0.0/16`
     - **Link-Local & Cloud Metadata**: `169.254.0.0/16`, `169.254.169.254`, `fe80::/10`
     - **Carrier-Grade NAT**: `100.64.0.0/10`
     - **IPv6 Unique Local**: `fc00::/7`
- Any target resolving to a prohibited address is rejected with `HTTP 400 Bad Request`.

### D. Self-Referencing Redirect Loop Defense
- **Rule**: A short link cannot redirect to the URL shortener instance itself.
- **Enforcement**: [`UrlSafetyValidator`](../backend/src/main/java/com/urlshortener/util/UrlSafetyValidator.java) compares the target host and port against `app.base-url`. Matches are rejected to prevent amplification loops.

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
- **Fast-Path Safety**: If an expired or deleted link is accessed, [`RedirectController`](../backend/src/main/java/com/urlshortener/controller/RedirectController.java) immediately evicts any lingering cache entry and returns `HTTP 410 Gone`.

### G. Privacy-Preserving IP Pseudonymization (HMAC-SHA256)
- Raw IP addresses are **never** stored in application tables.
- Click events are hashed using `HmacSHA256` with a server-side rotatable salt (`app.security.ip-salt`, minimum 32 characters enforced on boot), protecting the 32-bit IPv4 address space against precomputed rainbow table attacks.

### H. Authentication & Password Security
- **JWT Key Strength**: [`JwtConfiguration`](../backend/src/main/java/com/urlshortener/config/JwtConfiguration.java) validates on application startup that `app.jwt.secret` is at least 256 bits (32 bytes) and rejects insecure defaults (`secret`, `changeme`, etc.).
- **Password Hashing**: [`SecurityConfiguration`](../backend/src/main/java/com/urlshortener/config/SecurityConfiguration.java) explicitly sets the BCrypt work factor to **12 rounds**, resisting brute-force offline cracking.

---

## 3. Known Security Limitations & Operational Boundaries

1. **DNS Rebinding Vulnerability in Validate-at-Create Checks**:
   - The SSRF protection verifies hostnames and IP addresses during link creation (`POST /api/urls`). An adversary could configure a DNS record with a low TTL (e.g. 1 second) that initially points to a benign public IP address (`8.8.8.8`) to pass create-time validation, then immediately rebinds the DNS record to an internal IP (`169.254.169.254` or `127.0.0.1`).
   - *Mitigation in Production*: In an HTTP 302 redirect shortener, the client browser follows the redirect, not the shortener server. However, for features that scrape target metadata or generate link previews, validation must be re-evaluated at fetch time via a forward proxy with egress firewall rules.

2. **Distinct-Visitor Continuity on Salt Rotation**:
   - Rotating `app.security.ip-salt` is a security best practice that prevents long-term correlation attacks. However, changing the salt resets historical distinct-visitor linkage: subsequent clicks from the same visitor will produce a different hash and appear as a new visitor in analytics dashboards.

3. **Ingress Web Server Access Logs**:
   - While application databases store only HMAC-pseudonymized IP hashes, upstream reverse proxies (Nginx or AWS Application Load Balancers) record raw client IP addresses in their HTTP access logs (`$remote_addr`) by default. Full GDPR compliance requires configuring Nginx log filtering (e.g. `set $anonymized_ip ...` or disabling raw access logging).

---

## 4. Operational Takedown & Phishing Workflow

If an abusive or phishing URL is reported:
1. **Immediate API Invalidation**: Calling `DELETE /api/urls/{shortCode}` marks the link as deleted in PostgreSQL and triggers instant Redis cache eviction.
2. **Fast-Path Rejection**: Subsequent redirect requests immediately receive `HTTP 410 Gone`.
3. **Forensic Audit**: The `clicks` and `click_event_dedup` tables retain hashed IP audit trails to measure how many users were targeted prior to takedown without exposing visitor identity.
