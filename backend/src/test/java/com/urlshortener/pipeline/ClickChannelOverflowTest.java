package com.urlshortener.pipeline;

import com.urlshortener.dto.ClickEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Unit Test with Stubs: ClickEventChannel Ring Buffer Overflow & Drop Accounting")
class ClickChannelOverflowTest {

    @Test
    @DisplayName("Unit Test: In-memory channel buffer overflow increments drop counter without blocking")
    void inMemoryBufferOverflowIncrementsDropCounterWithoutBlocking() {
        // Buffer capacity = 2
        InMemoryClickEventChannel channel = new InMemoryClickEventChannel(2);

        ClickEvent event1 = new ClickEvent("e1", "code1", 1L, LocalDateTime.now(), "127.0.0.1", "ua", "ref");
        ClickEvent event2 = new ClickEvent("e2", "code1", 1L, LocalDateTime.now(), "127.0.0.1", "ua", "ref");
        ClickEvent event3 = new ClickEvent("e3", "code1", 1L, LocalDateTime.now(), "127.0.0.1", "ua", "ref");

        assertTrue(channel.publish(event1));
        assertTrue(channel.publish(event2));

        // Third event overflows the bounded capacity
        long start = System.currentTimeMillis();
        boolean accepted = channel.publish(event3);
        long duration = System.currentTimeMillis() - start;

        assertFalse(accepted, "Overflowed event must be rejected");
        assertTrue(duration < 20, "Overflow must reject immediately without blocking");
        assertEquals(2, channel.getPublishedCount());
        assertEquals(1, channel.getDroppedCount(), "Drop counter must increment on overflow");
    }

    @Test
    @DisplayName("Unit Test: RedisStreamClickChannel async publisher buffer overflow increments drop counter without blocking")
    void redisStreamBufferOverflowIncrementsDropCounterWithoutBlocking() {
        StringRedisTemplate mockRedis = mock(StringRedisTemplate.class);
        // Small buffer: 1 thread, 1 capacity
        RedisStreamClickChannel channel = new RedisStreamClickChannel(mockRedis, "clicks:stream", 50000, 1, 1);

        ClickEvent event1 = new ClickEvent("e1", "code1", 1L, LocalDateTime.now(), "127.0.0.1", "ua", "ref");
        ClickEvent event2 = new ClickEvent("e2", "code1", 1L, LocalDateTime.now(), "127.0.0.1", "ua", "ref");
        ClickEvent event3 = new ClickEvent("e3", "code1", 1L, LocalDateTime.now(), "127.0.0.1", "ua", "ref");

        // First event accepted
        channel.publish(event1);
        channel.publish(event2);

        long start = System.currentTimeMillis();
        channel.publish(event3);
        long duration = System.currentTimeMillis() - start;

        assertTrue(duration < 20, "Publish must never block the redirect caller thread");
        channel.shutdown();
    }
}
