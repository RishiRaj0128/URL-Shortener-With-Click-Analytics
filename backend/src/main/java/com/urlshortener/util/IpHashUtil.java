package com.urlshortener.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Utility for hashing IP addresses for privacy using HMAC-SHA256.
 * 
 * Privacy & Security Architecture:
 * - Unsalted SHA-256 is vulnerable to rainbow table precomputation because the IPv4
 *   address space is strictly limited to 2^32 (~4.3 billion) addresses.
 * - This implementation replaces plain SHA-256 with HMAC-SHA256 using a high-entropy secret
 *   key from configuration and a rotatable salt.
 * - Output format: 64 hex characters (256-bit digest), fully compatible with existing
 *   `ip_hash VARCHAR(64)` database column.
 */
@Component
public class IpHashUtil {

    private static final String DEFAULT_SECRET = "urlshortener-ip-hmac-secret-min-256-bits-entropy-key!";
    private static final String DEFAULT_SALT = "salt-2026-v1";

    private static volatile String activeSecret = DEFAULT_SECRET;
    private static volatile String activeSalt = DEFAULT_SALT;

    public IpHashUtil() {
        this(DEFAULT_SECRET, DEFAULT_SALT);
    }

    @Autowired
    public IpHashUtil(
            @Value("${app.security.ip-hash-secret:${app.jwt.secret:urlshortener-ip-hmac-secret-min-256-bits-entropy-key!}}") String secret,
            @Value("${app.security.ip-hash-salt:salt-2026-v1}") String salt) {
        setSecretAndSalt(secret, salt);
    }

    /**
     * Updates active secret and rotatable salt.
     */
    public static void setSecretAndSalt(String secret, String salt) {
        if (secret != null && !secret.isBlank()) {
            activeSecret = secret;
        }
        if (salt != null && !salt.isBlank()) {
            activeSalt = salt;
        }
    }

    /**
     * Hash an IP address using HMAC-SHA256 with the active configured secret and salt.
     * Returns the hex-encoded 64-character hash.
     */
    public static String hashIp(String ip) {
        return hashIp(ip, activeSecret, activeSalt);
    }

    /**
     * Pure function: Hash an IP address using HMAC-SHA256 with an explicit secret and rotatable salt.
     */
    public static String hashIp(String ip, String secret, String salt) {
        if (ip == null || ip.isBlank()) {
            return null;
        }

        String effectiveSecret = (secret != null && !secret.isBlank()) ? secret : DEFAULT_SECRET;
        String effectiveSalt = (salt != null) ? salt : "";
        String combinedKey = effectiveSecret + ":" + effectiveSalt;

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(combinedKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(ip.trim().getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 for IP", e);
        }
    }

    /**
     * Convert byte array to hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(bytes.length * 2);
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
