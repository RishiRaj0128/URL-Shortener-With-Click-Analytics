package com.urlshortener.security;

import com.urlshortener.config.JwtConfiguration;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * Service for creating, parsing, and validating JWT tokens.
 * 
 * Uses JJWT library for secure token handling.
 * Tokens contain email as subject and expiration time.
 */
@Service
public class JwtTokenProvider {
    
    private final SecretKey secretKey;
    private final JwtParser jwtParser;
    private final long jwtExpiration;
    
    @Autowired
    public JwtTokenProvider(JwtConfiguration jwtConfiguration,
                          SecretKey secretKey,
                          JwtParser jwtParser) {
        this.secretKey = secretKey;
        this.jwtParser = jwtParser;
        this.jwtExpiration = jwtConfiguration.getJwtExpiration();
    }
    
    /**
     * Generate a JWT token for a user email.
     * Token expires after jwtExpiration milliseconds.
     */
    public String generateToken(String email) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpiration);
        
        return Jwts.builder()
            .subject(email)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(secretKey)
            .compact();
    }
    
    /**
     * Extract email from JWT token.
     * Throws JwtException if token is invalid or expired.
     */
    public String getEmailFromToken(String token) {
        Claims claims = jwtParser.parseSignedClaims(token).getPayload();
        return claims.getSubject();
    }
    
    /**
     * Validate JWT token.
     * Checks signature, expiration, and well-formedness.
     */
    public boolean validateToken(String token) {
        try {
            jwtParser.parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
