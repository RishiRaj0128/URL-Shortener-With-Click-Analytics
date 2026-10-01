package com.urlshortener.config;

import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;

/**
 * JWT (JSON Web Token) configuration.
 * 
 * Provides beans for JWT creation and validation.
 * Uses JJWT library (io.jsonwebtoken) for industry-standard implementation.
 */
@Configuration
public class JwtConfiguration {

  @Value("${app.jwt.secret}")
  private String jwtSecret;

  @Value("${app.jwt.expiration}")
  private long jwtExpiration;

  @Bean
  public SecretKey secretKey() {
    // HMAC-SHA256 requires 256+ bits (32+ bytes)
    return Keys.hmacShaKeyFor(jwtSecret.getBytes());
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
