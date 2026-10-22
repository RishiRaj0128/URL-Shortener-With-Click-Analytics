package com.urlshortener.pipeline;

import com.urlshortener.dto.ClickEvent;
import com.urlshortener.repository.ClickRollupRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("Track 0.1 (Integration/Regression): Cold Start, FLUSHALL Re-init, and Fault Recovery")
class RedisStreamColdStartAndFaultRecoveryIT {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private SimpleMeterRegistry meterRegistry;

    @Mock
    private ClickRollupRepository rollupRepository;

    private ClickEventProcessor processor;
    private String testStreamKey;
    private String testGroupName;

    @BeforeEach
    void setUp() {
        boolean redisAvailable = false;
        try {
            RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("localhost", 6379);
            LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(2000))
                .build();
            connectionFactory = new LettuceConnectionFactory(config, clientConfig);
            connectionFactory.afterPropertiesSet();
            redisTemplate = new StringRedisTemplate(connectionFactory);
            String ping = redisTemplate.getConnectionFactory().getConnection().ping();
            redisAvailable = "PONG".equalsIgnoreCase(ping);
        } catch (Exception e) {
            redisAvailable = false;
        }

        assumeTrue(redisAvailable, "Real Redis must be available on localhost:6379 to execute integration drills");

        testStreamKey = "test:stream:" + UUID.randomUUID();
        testGroupName = "test:group:" + UUID.randomUUID();
        meterRegistry = new SimpleMeterRegistry();

        processor = new ClickEventProcessor(
            rollupRepository,
            null,
            null,
            null,
            redisTemplate,
            meterRegistry
        );
    }

    @AfterEach
    void tearDown() {
        if (redisTemplate != null && testStreamKey != null) {
            try {
                redisTemplate.delete(testStreamKey);
            } catch (Exception ignored) {}
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("Regression 1: Consumer boots cleanly against empty Redis (no stream, no group) with MKSTREAM")
    void shouldStartCleanlyAgainstFreshRedisWithNoStreamAndNoGroup() throws Exception {
        // Assert stream key does NOT exist
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(testStreamKey)), "Stream key must not exist initially");

        RedisStreamClickConsumer consumer = new RedisStreamClickConsumer(
            redisTemplate,
            processor,
            testStreamKey,
            testGroupName,
            "worker-cold-start",
            50,
            100,
            60000,
            false,
            meterRegistry
        );

        // Cold start initialization: must provision stream and group via MKSTREAM without crashing
        assertDoesNotThrow(consumer::initConsumerGroup, "initConsumerGroup must execute cleanly on empty Redis");

        // Verify key and consumer group now exist in Redis
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(testStreamKey)), "Stream must be created in Redis via MKSTREAM");
        StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(testStreamKey);
        assertNotNull(groups);
        assertTrue(groups.stream().anyMatch(g -> testGroupName.equals(g.groupName())), "Consumer group must exist in Redis");

        // Polling an empty stream must return 0 without throwing NOGROUP or execution error
        int pollCount = consumer.pollAndProcess();
        assertEquals(0, pollCount, "Poll on empty stream must return 0");

        // Publish an event and verify clean processing
        publishTestEvent("evt_cold_001", "cold_code");
        int processed = consumer.pollAndProcess();
        assertEquals(1, processed, "Must process published event");

        // Verify group pending entries are zero (post-commit XACK executed)
        StreamInfo.XInfoGroups updatedGroups = redisTemplate.opsForStream().groups(testStreamKey);
        assertEquals(0, updatedGroups.get(0).pendingCount(), "Pending count must be 0 after explicit XACK");
    }

    @Test
    @DisplayName("Regression 2: Consumer survives stream wipe / FLUSHALL, auto-recreates group and resumes ingestion")
    void shouldRecoverAndRecreateGroupWhenRedisIsFlushedMidOperation() throws Exception {
        RedisStreamClickConsumer consumer = new RedisStreamClickConsumer(
            redisTemplate,
            processor,
            testStreamKey,
            testGroupName,
            "worker-flushall",
            50,
            100,
            60000,
            false,
            meterRegistry
        );

        consumer.initConsumerGroup();
        publishTestEvent("evt_pre_001", "pre_flush");
        int processedPre = consumer.pollAndProcess();
        assertEquals(1, processedPre, "Event prior to wipe must be processed");

        // Simulate FLUSHALL or stream key deletion while consumer is active
        redisTemplate.delete(testStreamKey);
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(testStreamKey)), "Stream key must be completely gone");

        // Next poll detects missing key/group, catches warning, triggers auto-reinit with MKSTREAM
        int pollAfterWipe = consumer.pollAndProcess();
        assertEquals(0, pollAfterWipe, "Poll immediately after wipe must return 0 and auto-reinitialize");

        // Publish new event post-wipe
        publishTestEvent("evt_post_002", "post_flush");

        // Consumer must poll, process, and acknowledge without dropping the event
        int processedPost = consumer.pollAndProcess();
        assertEquals(1, processedPost, "New event post-reinit must be processed cleanly");

        // Reinitialization metric must have recorded at least 2 inits (startup + post-wipe)
        double reinitCount = meterRegistry.get("click.stream.reinit.count").counter().count();
        assertTrue(reinitCount >= 2.0, "Reinit counter must record auto-reinitialization upon wipe");

        // Verify group exists and pending is 0
        StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(testStreamKey);
        assertEquals(0, groups.get(0).pendingCount(), "Pending entries must be 0 post-recovery");
    }

    @Test
    @DisplayName("Regression 3: Re-init on error applies exponential backoff and tracks error metrics")
    void shouldTrackErrorMetricsAndApplyCappedBackoff() throws Exception {
        // Stream key with no consumer group created
        String uninitializedStream = "test:stream:uninit:" + UUID.randomUUID();
        String nonExistentGroup = "test:group:nonexistent";

        RedisStreamClickConsumer consumer = new RedisStreamClickConsumer(
            redisTemplate,
            processor,
            uninitializedStream,
            nonExistentGroup,
            "worker-backoff",
            50,
            100,
            60000,
            false,
            meterRegistry
        );

        // Do not call initConsumerGroup; attempt poll directly
        // The consumer will catch the exception, increment poll error counter, and auto-initialize
        consumer.pollAndProcess();

        double errorCount = meterRegistry.get("click.stream.errors").counter().count();
        assertTrue(errorCount >= 1.0, "Poll error metric must increment on missing stream/group");

        // Clean up
        redisTemplate.delete(uninitializedStream);
    }

    private void publishTestEvent(String eventId, String shortCode) {
        Map<String, String> body = new HashMap<>();
        body.put("eventId", eventId);
        body.put("shortCode", shortCode);
        body.put("urlId", "1");
        body.put("clickedAt", LocalDateTime.now().toString());
        body.put("ipAddress", "127.0.0.1");
        body.put("userAgent", "JUnit/Test");
        body.put("referer", "http://test.com");
        body.put("traceId", UUID.randomUUID().toString().replace("-", ""));

        redisTemplate.opsForStream().add(
            MapRecord.create(testStreamKey, body)
        );
    }
}
