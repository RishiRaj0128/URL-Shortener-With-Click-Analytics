package com.urlshortener.util;

import com.urlshortener.exception.UrlShortenerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validator for target URLs and custom aliases.
 * 
 * Enforces:
 * - Protocol allowlist: http and https only (rejects ftp, file, javascript, data, etc.)
 * - Maximum URL length: 2048 characters
 * - SSRF protection: blocks loopback, private RFC-1918 subnets, carrier-grade NAT,
 *   cloud metadata services (169.254.169.254), link-local subnets, and local domain suffixes.
 * - Self-referencing loop prevention: blocks targets pointing back to this service instance.
 * - Custom alias validation: 3-30 alphanumeric chars, hyphens, and underscores, rejecting reserved keywords.
 */
@Component
public class UrlSafetyValidator {

    private static final Logger log = LoggerFactory.getLogger(UrlSafetyValidator.class);

    public static final int MAX_URL_LENGTH = 2048;

    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,30}$");

    private static final Set<String> RESERVED_KEYWORDS = Set.of(
        "api", "auth", "login", "signup", "register", "logout",
        "admin", "health", "ready", "metrics", "actuator", "prometheus",
        "system", "swagger", "swagger-ui", "v3", "docs", "chaos",
        "urls", "analytics", "shorten", "favicon.ico", "robots.txt",
        "static", "assets", "public", "index", "root", "error"
    );

    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
        "localhost",
        "127.0.0.1",
        "::1",
        "[::1]",
        "0.0.0.0",
        "169.254.169.254",
        "metadata.google.internal",
        "instance-data"
    );

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    /**
     * Default constructor.
     */
    public UrlSafetyValidator() {
    }

    /**
     * Constructor with explicit baseUrl for isolated unit testing.
     */
    public UrlSafetyValidator(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Validates that the target URL is secure, syntactically well-formed, and not an SSRF or redirect loop vector.
     *
     * @param rawUrl The URL to validate
     * @throws UrlShortenerException with HttpStatus.BAD_REQUEST if invalid
     */
    public void validateUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new UrlShortenerException("URL cannot be empty", HttpStatus.BAD_REQUEST);
        }

        String trimmed = rawUrl.trim();

        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new UrlShortenerException(
                "URL exceeds maximum allowed length of " + MAX_URL_LENGTH + " characters (length: " + trimmed.length() + ")",
                HttpStatus.BAD_REQUEST
            );
        }

        // Scheme check: only http and https allowed
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new UrlShortenerException(
                "Invalid URL protocol: only HTTP and HTTPS are permitted (ftp, file, javascript, and data schemes are rejected)",
                HttpStatus.BAD_REQUEST
            );
        }

        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (Exception e) {
            throw new UrlShortenerException("Invalid URL syntax: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new UrlShortenerException("Invalid URL protocol: " + scheme, HttpStatus.BAD_REQUEST);
        }

        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) {
            throw new UrlShortenerException("Invalid URL: host cannot be empty", HttpStatus.BAD_REQUEST);
        }

        String hostLower = host.toLowerCase(Locale.ROOT);

        // Explicit blocked hostnames
        if (BLOCKED_HOSTNAMES.contains(hostLower)
                || hostLower.endsWith(".localhost")
                || hostLower.endsWith(".local")
                || hostLower.endsWith(".internal")
                || hostLower.endsWith(".arpa")) {
            throw new UrlShortenerException(
                "Prohibited host: loopback, local, and metadata endpoints are blocked (SSRF protection)",
                HttpStatus.BAD_REQUEST
            );
        }

        // Self-referencing check
        if (isSelfReferencing(uri)) {
            throw new UrlShortenerException(
                "Self-referencing URLs pointing back to this service instance are prohibited to prevent redirect loops",
                HttpStatus.BAD_REQUEST
            );
        }

        // IP resolution SSRF verification
        validateHostIpResolution(host);
    }

    /**
     * Resolves host IP addresses and verifies they do not fall within private or loopback ranges.
     */
    private void validateHostIpResolution(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (isProhibitedIp(addr)) {
                    throw new UrlShortenerException(
                        "Target host resolves to prohibited internal or private IP address: " + addr.getHostAddress() + " (SSRF protection)",
                        HttpStatus.BAD_REQUEST
                    );
                }
            }
        } catch (UnknownHostException e) {
            // If DNS lookup fails (e.g. offline testing or synthetic test host), verify if the host is an IP literal
            if (isIpLiteral(host)) {
                throw new UrlShortenerException("Invalid or prohibited IP address: " + host, HttpStatus.BAD_REQUEST);
            }
            log.debug("DNS resolution for host '{}' could not be resolved during validation; allowing syntax.", host);
        }
    }

    /**
     * Tests if an IP address belongs to loopback, private RFC-1918, link-local, carrier-grade NAT, or cloud metadata.
     */
    public boolean isProhibitedIp(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
            return true;
        }

        byte[] bytes = addr.getAddress();
        if (bytes.length == 4) { // IPv4
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;

            // 0.0.0.0/8 (Current network)
            if (b0 == 0) return true;
            // 10.0.0.0/8 (Private)
            if (b0 == 10) return true;
            // 100.64.0.0/10 (Shared / Carrier-grade NAT RFC-6598)
            if (b0 == 100 && (b1 >= 64 && b1 <= 127)) return true;
            // 127.0.0.0/8 (Loopback)
            if (b0 == 127) return true;
            // 169.254.0.0/16 (Link-local & AWS/GCP/Azure Metadata 169.254.169.254)
            if (b0 == 169 && b1 == 254) return true;
            // 172.16.0.0/12 (Private 172.16.0.0 - 172.31.255.255)
            if (b0 == 172 && (b1 >= 16 && b1 <= 31)) return true;
            // 192.168.0.0/16 (Private)
            if (b0 == 192 && b1 == 168) return true;
            // 255.255.255.255 (Broadcast)
            if (b0 == 255 && b1 == 255) return true;
        } else if (bytes.length == 16) { // IPv6
            // ::1 (Loopback)
            if (addr.isLoopbackAddress()) return true;
            // fc00::/7 (Unique Local Address RFC-4193)
            int firstByte = bytes[0] & 0xFF;
            if ((firstByte & 0xFE) == 0xFC) return true;
            // fe80::/10 (Link-Local)
            if (firstByte == 0xFE && (bytes[1] & 0xC0) == 0x80) return true;
        }

        return false;
    }

    private boolean isIpLiteral(String host) {
        return host.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$") || host.contains(":");
    }

    private boolean isSelfReferencing(URI targetUri) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            URI base = URI.create(baseUrl.trim());
            String targetHost = targetUri.getHost();
            String baseHost = base.getHost();
            if (targetHost != null && baseHost != null && targetHost.equalsIgnoreCase(baseHost)) {
                int targetPort = targetUri.getPort() != -1 ? targetUri.getPort() : (targetUri.getScheme().equalsIgnoreCase("https") ? 443 : 80);
                int basePort = base.getPort() != -1 ? base.getPort() : (base.getScheme().equalsIgnoreCase("https") ? 443 : 80);
                return targetPort == basePort;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Validates custom alias format and ensures it does not collide with reserved endpoints.
     */
    public void validateCustomAlias(String alias) {
        if (alias == null || !ALIAS_PATTERN.matcher(alias).matches()) {
            throw new UrlShortenerException(
                "Custom alias must be between 3 and 30 characters and contain only alphanumeric characters, underscores, or hyphens",
                HttpStatus.BAD_REQUEST
            );
        }

        if (RESERVED_KEYWORDS.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new UrlShortenerException(
                "Custom alias '" + alias + "' is a reserved keyword and cannot be used",
                HttpStatus.BAD_REQUEST
            );
        }
    }
}
