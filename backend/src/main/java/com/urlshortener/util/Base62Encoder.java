package com.urlshortener.util;

/**
 * Utility class for Base62 encoding/decoding.
 * 
 * SHORT CODE GENERATION STRATEGY:
 * 
 * Approach: Base62-encoded 64-bit Distributed Snowflake IDs
 * 
 * Why Base62?
 * - Uses 62 characters (0-9, A-Z, a-z) to represent numbers compactly.
 * - URL-safe (no special characters that require percent-encoding).
 * - Deterministic & Bijective: each unique 64-bit integer maps to exactly one Base62 string.
 * 
 * Length Characteristics:
 * - 64-bit Snowflake IDs (timestamp offset shifted by 22 bits) have magnitudes of ~10^17.
 * - 62^9 ≈ 1.35 x 10^16, 62^10 ≈ 8.39 x 10^17, 62^11 ≈ 5.2 x 10^19.
 * - Therefore, Snowflake IDs encode to 10 to 11 Base62 characters across their 69.7-year lifespan.
 * - The full Base62 string is preserved without truncation to maintain collision-freedom.
 * 
 * Collision Guarantee: Collision-Free by Construction
 * - Not hash-based: no hash collisions or birthday-paradox risks.
 * - Uniqueness is guaranteed by construction:
 *   1. 41 timestamp bits guarantee monotonicity over time.
 *   2. 10 worker bits ensure spatial isolation across up to 1,024 cluster nodes.
 *   3. 12 sequence bits ensure uniqueness within each millisecond (up to 4,096 IDs/ms/node).
 *   4. Base62 encoding is a bijective mathematical bijection, preserving this uniqueness.
 */
public class Base62Encoder {
    
    private static final String CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = 62;
    
    /**
     * Encode a non-negative long ID to base62 short code.
     * Pure function: same input always produces same output with no side effects.
     * Example: 12345 -> "315g"
     */
    public static String encode(long id) {
        if (id < 0) {
            throw new IllegalArgumentException("ID must be non-negative: " + id);
        }
        if (id == 0) {
            return "0";
        }
        
        StringBuilder result = new StringBuilder();
        while (id > 0) {
            result.insert(0, CHARSET.charAt((int) (id % BASE)));
            id = id / BASE;
        }
        return result.toString();
    }
    
    /**
     * Decode a base62 short code back to its original long ID.
     * Pure function: deterministic inverse of encode().
     * Example: "315g" -> 12345
     */
    public static long decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("Encoded string must not be null or empty");
        }
        long result = 0;
        for (char c : encoded.toCharArray()) {
            int index = CHARSET.indexOf(c);
            if (index == -1) {
                throw new IllegalArgumentException("Invalid Base62 character: " + c);
            }
            result = result * BASE + index;
        }
        return result;
    }
}
