package com.urlshortener.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IpHashUtil.
 */
class IpHashUtilTest {
    
    @Test
    void testHashIpConsistency() {
        String ip = "192.168.1.1";
        String hash1 = IpHashUtil.hashIp(ip);
        String hash2 = IpHashUtil.hashIp(ip);
        assertEquals(hash1, hash2);
    }
    
    @Test
    void testHashIpIsOneWay() {
        String ip = "10.0.0.1";
        String hash = IpHashUtil.hashIp(ip);
        // Hash should not contain original IP
        assertFalse(hash.contains(ip));
    }
    
    @Test
    void testHashIpLength() {
        String ip = "127.0.0.1";
        String hash = IpHashUtil.hashIp(ip);
        // SHA-256 produces 64 hex characters
        assertEquals(64, hash.length());
    }
    
    @Test
    void testHashIpDifferentForDifferentIPs() {
        String hash1 = IpHashUtil.hashIp("192.168.1.1");
        String hash2 = IpHashUtil.hashIp("192.168.1.2");
        assertNotEquals(hash1, hash2);
    }
    
    @Test
    void testHashIpNull() {
        assertNull(IpHashUtil.hashIp(null));
    }
    
    @Test
    void testHashIpEmpty() {
        assertNull(IpHashUtil.hashIp(""));
    }
}
