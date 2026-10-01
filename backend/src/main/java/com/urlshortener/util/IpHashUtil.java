package com.urlshortener.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility for hashing IP addresses for privacy.
 * 
 * Privacy Requirement: Never store raw IP addresses
 * - GDPR compliance: IPs are personal data
 * - Security: Reduces risk if database is compromised
 * - Ethics: Protects user privacy
 * 
 * Strategy:
 * - Hash IPs using SHA-256
 * - Store hash in clicks table
 * - Hash is one-way; cannot recover original IP
 * - Still allows deduplication of clicks from same IP
 */
public class IpHashUtil {
    
    /**
     * Hash an IP address using SHA-256.
     * Returns the hex-encoded hash (64 characters for SHA-256).
     */
    public static String hashIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }
        
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(ip.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(encodedHash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
    
    /**
     * Convert byte array to hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
