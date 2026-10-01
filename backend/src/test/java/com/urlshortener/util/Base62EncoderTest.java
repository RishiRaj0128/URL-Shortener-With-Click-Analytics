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
}
