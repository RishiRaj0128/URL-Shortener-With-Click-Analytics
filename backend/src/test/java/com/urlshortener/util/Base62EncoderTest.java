package com.urlshortener.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Base62Encoder.
 */
class Base62EncoderTest {
    
    @Test
    void testEncodeDecode() {
        long originalId = 12345L;
        String encoded = Base62Encoder.encode(originalId);
        long decoded = Base62Encoder.decode(encoded);
        assertEquals(originalId, decoded);
    }
    
    @Test
    void testEncodeZero() {
        String encoded = Base62Encoder.encode(0);
        assertEquals("0", encoded);
    }
    
    @Test
    void testEncodeOne() {
        String encoded = Base62Encoder.encode(1);
        assertEquals("1", encoded);
    }
    
    @Test
    void testEncodeLargeNumber() {
        long largeId = 1000000000L;
        String encoded = Base62Encoder.encode(largeId);
        long decoded = Base62Encoder.decode(encoded);
        assertEquals(largeId, decoded);
    }
    
    @Test
    void testEncodedStringIsCompact() {
        String encoded = Base62Encoder.encode(1000000L);
        // Base62 should be more compact than decimal (7 digits)
        assertTrue(encoded.length() <= 7);
    }

    @Test
    void testSnowflakeMagnitudeEncodingLength() {
        // A typical Snowflake ID with ~10 months elapsed has value ~1.05 x 10^17
        long snowflakeScaleId = 105_000_000_000_000_000L;
        String encoded = Base62Encoder.encode(snowflakeScaleId);
        assertEquals(10, encoded.length(), "Snowflake-scale value must encode to exactly 10 characters");
        assertEquals(snowflakeScaleId, Base62Encoder.decode(encoded));

        // Long.MAX_VALUE = 9,223,372,036,854,775,807 (upper bound of signed 64-bit)
        String maxEncoded = Base62Encoder.encode(Long.MAX_VALUE);
        assertEquals(11, maxEncoded.length(), "Long.MAX_VALUE encodes to 11 Base62 characters");
        assertEquals(Long.MAX_VALUE, Base62Encoder.decode(maxEncoded));
    }
}
