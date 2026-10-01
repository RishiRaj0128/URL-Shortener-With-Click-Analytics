package com.urlshortener.util;

/**
 * Utility class for Base62 encoding/decoding.
 * 
 * SHORT CODE GENERATION STRATEGY:
 * 
 * Approach: Base62-encoded auto-incrementing IDs
 * 
 * Why Base62?
 * - Uses 62 characters (0-9, a-z, A-Z) to represent numbers
 * - More compact than decimal: 6 chars in base62 ≈ 56 billion unique values
 * - URL-safe (no special characters that need encoding)
 * - Deterministic: same ID always produces same short code
 * - No collision risk: IDs are unique from the database sequence
 * 
 * How it works:
 * 1. PostgreSQL generates unique auto-incrementing ID for each URL
 * 2. We encode this ID to base62 (typically 6 characters for most use cases)
 * 3. Result is URL-safe short code (e.g., "aBc123")
 * 
 * Collision Handling:
 * - Zero collisions: base62 encoding is deterministic
 * - Database constraints: UNIQUE on short_code prevents accidental duplicates
 * - In practice, we generate short code from the ID, so collisions are impossible
 * 
 * Alternative Considered: Deterministic Hash
 * - Could hash the original URL to generate short code
 * - ✗ Collision risk (birthday paradox)
 * - ✗ Longer codes needed for low collision probability
 * - ✗ No benefit since DB gives us guaranteed-unique IDs
 * 
 * Alternative Considered: Random Encoding
 * - Generate random strings and check for collisions
 * - ✗ Non-deterministic (same URL → different codes)
 * - ✗ Slower (requires collision checking on every creation)
 * - ✗ Wastes short codes on collisions
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
