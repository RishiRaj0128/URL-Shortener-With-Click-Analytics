package com.urlshortener.config;

import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * JWT (JSON Web Token) configuration.
 * 
 * Provides beans for JWT creation and validation.
 * Enforces startup security checks:
 * - Secret must have at least 256 bits (32 bytes)
 * - Rejects trivial weak defaults (e.g. "secret", "changeme", "admin")
 */
@Configuration
public class JwtConfiguration {

  private static final Logger log = LoggerFactory.getLogger(JwtConfiguration.class);

  public static final int MIN_KEY_BYTES = 32;

  private static final Set<String> PROHIBITED_DEFAULT_SECRETS = Set.of(
      "secret",
      "secretkey",
      "changeme",
      "password",
      "admin",
      "jwtsecret",
      "12345678901234567890123456789012",
      "abcdefghijklmnopqrstuvwxyz123456"
  );

  @Value("${app.jwt.secret}")
  private String jwtSecret;

  @Value("${app.jwt.expiration:86400000}")
  private long jwtExpiration;

  @Bean
  public SecretKey secretKey() {
    validateJwtSecret(jwtSecret);
    return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Validates that the provided secret meets cryptographic length and non-triviality standards.
   */
  public static void validateJwtSecret(String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException("JWT secret must not be blank.");
    }

    byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
    if (keyBytes.length < MIN_KEY_BYTES) {
      throw new IllegalStateException(
          "JWT secret must be at least 256 bits (" + MIN_KEY_BYTES + " bytes) for HMAC-SHA256. Found: " + keyBytes.length + " bytes."
      );
    }

    String lower = secret.trim().toLowerCase(Locale.ROOT);
    if (PROHIBITED_DEFAULT_SECRETS.contains(lower)) {
      throw new IllegalStateException(
          "Insecure JWT secret rejected! Prohibited trivial key: '" + secret + "'. Please configure a secure, high-entropy 256-bit key."
      );
    }

    if (secret.contains("change-in-production")) {
      log.warn("SECURITY WARNING: Detected default template placeholder in JWT secret. For production deployments, export JWT_SECRET as a secure environment variable.");
    }
  }

  @Bean
  public JwtParser jwtParser(SecretKey secretKey) {
    return Jwts.parser()
        .verifyWith(secretKey)
        .build();
  }

  public long getJwtExpiration() {
    return jwtExpiration;
  }
}
