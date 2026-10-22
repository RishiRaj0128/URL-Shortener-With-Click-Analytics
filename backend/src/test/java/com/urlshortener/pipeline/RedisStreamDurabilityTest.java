package com.urlshortener.pipeline;

import com.urlshortener.dto.ClickEvent;
import com.urlshortener.entity.ClickRollup;
import com.urlshortener.entity.ProcessedEvent;
import com.urlshortener.repository.ClickRollupRepository;
import com.urlshortener.repository.ProcessedEventRepository;
import com.urlshortener.service.AnomalyDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Track 0.1 (Unit): Redis Streams Pipeline & Durability Unit Tests (Mocks)")
class RedisStreamDurabilityTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOps;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private ClickRollupRepository rollupRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private AnomalyDetector anomalyDetector;

    private ClickEventProcessor processor;
    private final String streamKey = "clicks:stream";
    private final String groupName = "clicks:consumer-group";
    private final String consumerName = "worker-isolated-proc";

    // In-memory test store simulating durable DB
    private final Map<String, ProcessedEvent> durableProcessedStore = new ConcurrentHashMap<>();
    private final Map<String, ClickRollup> rollupStore = new ConcurrentHashMap<>();
    private final Map<String, String> redisKvStore = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForStream()).thenReturn(streamOps);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // Redis SET NX simulation
        lenient().when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenAnswer(inv -> {
                String key = inv.getArgument(0);
                String val = inv.getArgument(1);
                return redisKvStore.putIfAbsent(key, val) == null;
            });

        // Durable DB store simulation
        lenient().when(processedEventRepository.existsByEventId(anyString()))
            .thenAnswer(inv -> durableProcessedStore.containsKey(inv.getArgument(0)));

        lenient().when(processedEventRepository.save(any(ProcessedEvent.class)))
            .thenAnswer(inv -> {
                ProcessedEvent e = inv.getArgument(0);
                durableProcessedStore.put(e.getEventId(), e);
                return e;
            });

        // Rollup store simulation
        lenient().when(rollupRepository.findByShortCodeAndWindowStart(anyString(), any(LocalDateTime.class)))
            .thenAnswer(inv -> {
                String code = inv.getArgument(0);
                LocalDateTime start = inv.getArgument(1);
                return Optional.ofNullable(rollupStore.get(code + "_" + start));
            });

        lenient().when(rollupRepository.save(any(ClickRollup.class)))
            .thenAnswer(inv -> {
                ClickRollup r = inv.getArgument(0);
                rollupStore.put(r.getShortCode() + "_" + r.getWindowStart(), r);
                return r;
            });

        // Clean anomaly detector mocks
        lenient().when(anomalyDetector.evaluateClickVelocity(any()))
            .thenReturn(new AnomalyDetector.AnomalyResult(false, null, null, 0.0));
        lenient().when(anomalyDetector.evaluateBucketZScore(anyString(), anyLong()))
            .thenReturn(new AnomalyDetector.AnomalyResult(false, null, null, 0.0));

        processor = new ClickEventProcessor(
            rollupRepository,
            processedEventRepository,
            null,
            anomalyDetector,
            redisTemplate
        );
    }

    @Test
    @DisplayName("Track 0.1 (1): Consumer-group read with XACK only after commit")
    void testConsumerGroupReadWithXackOnlyAfterCommit() throws Exception {
        String eventId = "evt_xack_test_1";
        String shortCode = "commitAckTest";
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);

        Map<Object, Object> fields = new HashMap<>();
        fields.put("eventId", eventId);
        fields.put("shortCode", shortCode);
        fields.put("urlId", "101");
        fields.put("timestamp", now.toString());
        fields.put("ip", "192.168.1.1");
        fields.put("userAgent", "Mozilla/5.0");
        fields.put("referrer", "https://juspay.in");

        RecordId recordId = RecordId.of("1727670000000-1");
        MapRecord<String, Object, Object> record = MapRecord.create(streamKey, fields).withId(recordId);

        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
            .thenReturn(Collections.singletonList(record));

        RedisStreamClickConsumer consumer = new RedisStreamClickConsumer(
            redisTemplate,
            processor,
            streamKey,
            groupName,
            "ack-verification-worker",
            10,
            500,
            60000,
            false
        );

        // Case A: Successful processing -> explicit XACK MUST be issued
        int processed = consumer.pollAndProcess();
        assertEquals(1, processed, "Successfully processed batch should return count of 1");
        verify(streamOps, times(1)).acknowledge(eq(streamKey), eq(groupName), eq(recordId));
        assertEquals(1, consumer.getAckedCount(), "Acked count should be incremented to 1");

        // Case B: Transaction failure before commit -> XACK MUST NOT be issued
        ClickEventProcessor failingProcessor = mock(ClickEventProcessor.class);
        doThrow(new RuntimeException("Simulated DB connection failure during transaction commit"))
            .when(failingProcessor).process(any(ClickEvent.class), anyString());

        RedisStreamClickConsumer failingConsumer = new RedisStreamClickConsumer(
            redisTemplate,
            failingProcessor,
            streamKey,
            groupName,
            "failing-worker",
            10,
            500,
            60000,
            false
        );

        RecordId unackedRecordId = RecordId.of("1727670000000-2");
        Map<Object, Object> fields2 = new HashMap<>(fields);
        fields2.put("eventId", "evt_uncommitted");
        MapRecord<String, Object, Object> unackedRecord = MapRecord.create(streamKey, fields2).withId(unackedRecordId);

        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
            .thenReturn(Collections.singletonList(unackedRecord));

        int failingProcessed = failingConsumer.pollAndProcess();
        assertEquals(0, failingProcessed, "Failed transaction should process 0 committed events");
        // Verify XACK was NEVER called for the failing record!
        verify(streamOps, never()).acknowledge(eq(streamKey), eq(groupName), eq(unackedRecordId));
        assertEquals(0, failingConsumer.getAckedCount(), "Failed transaction must NOT acknowledge message");
    }

    @Test
    @DisplayName("Track 0.1 (2): Kill of a SEPARATE consumer process mid-batch with exact final totals")
    void testKillOfSeparateConsumerProcessMidBatchWithExactFinalTotals() throws Exception {
        int totalEvents = 20;
        int crashAfterN = 10;
        String shortCode = "juspayPromo";
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);

        // Generate N stream records
        List<MapRecord<String, Object, Object>> initialBatch = new ArrayList<>();
        List<MapRecord<String, Object, Object>> pendingUnackedRecords = new ArrayList<>();
        List<PendingMessage> pendingMessages = new ArrayList<>();

        for (int i = 0; i < totalEvents; i++) {
            String eventId = "evt_durability_" + i;
            Map<Object, Object> fields = new HashMap<>();
            fields.put("eventId", eventId);
            fields.put("shortCode", shortCode);
            fields.put("urlId", "101");
            fields.put("timestamp", now.toString());
            fields.put("ip", "192.168.1." + (i % 5));
            fields.put("userAgent", "Mozilla/5.0");
            fields.put("referrer", "https://juspay.in");

            RecordId recordId = RecordId.of("1727670000000-" + i);
            MapRecord<String, Object, Object> record = MapRecord.create(streamKey, fields).withId(recordId);
            initialBatch.add(record);

            // Records 10 to 19 were read by crashed worker but NEVER acknowledged (XACK never received)
            if (i >= crashAfterN) {
                pendingUnackedRecords.add(record);
                pendingMessages.add(new PendingMessage(
                    recordId,
                    Consumer.from(groupName, consumerName),
                    Duration.ofMillis(70000), // Idle for 70 seconds (> 60s idle threshold)
                    1
                ));
            }
        }

        // Setup Consumer 1 (Worker Process Alpha - will crash mid-batch after processing exactly 10 events)
        RedisStreamClickConsumer consumer1 = new RedisStreamClickConsumer(
            redisTemplate,
            processor,
            streamKey,
            groupName,
            "worker-proc-alpha",
            50,
            1000,
            60000,
            false
        );

        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
            .thenReturn(initialBatch);

        // Enable simulated crash mid-batch
        consumer1.enableCrashSimulation(crashAfterN);

        // Run batch on Consumer 1 -> throws simulated crash exception at record #10
        assertThrows(RuntimeException.class, () -> consumer1.pollAndProcess());

        // Verify state at crash point: exactly 10 processed & acked, 10 pending in Redis
        assertEquals(10, consumer1.getAckedCount(), "Consumer 1 must have acknowledged exactly 10 events before dying");
        ClickRollup midCrashRollup = rollupStore.get(shortCode + "_" + now);
        assertNotNull(midCrashRollup);
        assertEquals(10, midCrashRollup.getLegitimateClicks(), "Rollup must show exactly 10 clicks processed before crash");

        // Now: Spin up Consumer 2 (Worker Process Beta - simulating independent restarted consumer process)
        String recoveryWorkerName = "worker-proc-beta-recovery";
        RedisStreamClickConsumer consumer2 = new RedisStreamClickConsumer(
            redisTemplate,
            processor,
            streamKey,
            groupName,
            recoveryWorkerName,
            50,
            1000,
            60000,
            false
        );

        // Mock pending entries and range read for recovery worker
        when(streamOps.pending(eq(streamKey), eq(groupName), any(Range.class), anyLong()))
            .thenReturn(new PendingMessages(groupName, pendingMessages));

        for (MapRecord<String, Object, Object> pendingRec : pendingUnackedRecords) {
            when(streamOps.range(eq(streamKey), eq(Range.just(pendingRec.getId().getValue()))))
                .thenReturn(Collections.singletonList(pendingRec));
        }

        // Consumer 2 triggers pending recovery (XAUTOCLAIM/XPENDING)
        int recovered = consumer2.recoverPendingEntries();

        // Assert all remaining 10 events were recovered, processed, and acknowledged
        assertEquals(10, recovered, "Consumer 2 must recover all 10 unacknowledged pending messages");
        assertEquals(10, consumer2.getAckedCount(), "Consumer 2 must acknowledge all 10 recovered messages");

        // Verify final rollup: EXACTLY 20 events. Zero lost, zero double counted!
        ClickRollup finalRollup = rollupStore.get(shortCode + "_" + now);
        assertEquals(totalEvents, finalRollup.getLegitimateClicks(), "Final rollup MUST equal exactly 20 (zero loss, zero double-count)");
        assertEquals(totalEvents, durableProcessedStore.size(), "Processed events table must contain exactly 20 distinct IDs");
    }

    @Test
    @DisplayName("Track 0.1 (3): Forced redelivery counted once (deduplication & idempotency)")
    void testForcedRedeliveryCountedOnce() {
        String eventId = "evt_forced_redelivery_999";
        String shortCode = "idempotentLink";
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);

        ClickEvent event = new ClickEvent(
            eventId,
            shortCode,
            202L,
            now,
            "10.20.30.40",
            "Mozilla/5.0",
            "https://juspay.in"
        );

        // First delivery: should process cleanly
        boolean firstDelivery = processor.process(event, "worker-node-1");
        assertTrue(firstDelivery, "First delivery must be processed and committed");

        ClickRollup rollupAfterFirst = rollupStore.get(shortCode + "_" + now);
        assertNotNull(rollupAfterFirst);
        assertEquals(1, rollupAfterFirst.getLegitimateClicks());

        // Second delivery (simulating queue redelivery after network glitch)
        boolean secondDelivery = processor.process(event, "worker-node-2");
        assertFalse(secondDelivery, "Second delivery must be identified as duplicate and dropped by dedup store");

        // Assert rollup count remained strictly 1
        ClickRollup rollupAfterSecond = rollupStore.get(shortCode + "_" + now);
        assertEquals(1, rollupAfterSecond.getLegitimateClicks(), "Duplicate event must NOT increment rollup count");
        assertEquals(1, processor.getDuplicateCount(), "Duplicate counter must record the redelivery attempt");
    }

    @Test
    @DisplayName("Track 0.1 (4): XAUTOCLAIM / XPENDING recovery of idle messages beyond threshold")
    void testXautoclaimPendingRecovery() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        String shortCode = "xautoclaimTarget";

        // Create 2 pending messages: 1 old (idle 90s > 60s threshold), 1 fresh (idle 10s < 60s threshold)
        RecordId oldId = RecordId.of("1727670000000-100");
        RecordId freshId = RecordId.of("1727670000000-101");

        PendingMessage oldPending = new PendingMessage(
            oldId,
            Consumer.from(groupName, "crashed-worker"),
            Duration.ofMillis(90000), // 90s idle -> MUST be claimed
            2
        );
        PendingMessage freshPending = new PendingMessage(
            freshId,
            Consumer.from(groupName, "active-worker"),
            Duration.ofMillis(10000), // 10s idle -> MUST NOT be claimed
            1
        );

        when(streamOps.pending(eq(streamKey), eq(groupName), any(Range.class), anyLong()))
            .thenReturn(new PendingMessages(groupName, Arrays.asList(oldPending, freshPending)));

        Map<Object, Object> oldFields = new HashMap<>();
        oldFields.put("eventId", "evt_xautoclaim_recovered");
        oldFields.put("shortCode", shortCode);
        oldFields.put("urlId", "303");
        oldFields.put("timestamp", now.toString());
        oldFields.put("ip", "10.0.0.1");
        oldFields.put("userAgent", "Mozilla/5.0");
        oldFields.put("referrer", "https://juspay.in");
        MapRecord<String, Object, Object> oldRecord = MapRecord.create(streamKey, oldFields).withId(oldId);

        when(streamOps.range(eq(streamKey), eq(Range.just(oldId.getValue()))))
            .thenReturn(Collections.singletonList(oldRecord));

        RedisStreamClickConsumer recoveryConsumer = new RedisStreamClickConsumer(
            redisTemplate,
            processor,
            streamKey,
            groupName,
            "xautoclaim-recovery-agent",
            50,
            1000,
            60000, // 60s idle threshold
            false
        );

        int recoveredCount = recoveryConsumer.recoverPendingEntries();

        // Old message (> 60s) claimed and acknowledged
        assertEquals(1, recoveredCount, "Only the idle message exceeding 60s threshold should be recovered");
        assertEquals(1, recoveryConsumer.getAckedCount(), "Recovered message must be acknowledged");
        verify(streamOps, times(1)).acknowledge(eq(streamKey), eq(groupName), eq(oldId));
        // Fresh message (< 60s) must NOT be acknowledged or ranged
        verify(streamOps, never()).range(eq(streamKey), eq(Range.just(freshId.getValue())));
        verify(streamOps, never()).acknowledge(eq(streamKey), eq(groupName), eq(freshId));

        // Verify stored in DB
        ClickRollup rollup = rollupStore.get(shortCode + "_" + now);
        assertNotNull(rollup);
        assertEquals(1, rollup.getLegitimateClicks(), "Recovered event must be committed to DB rollup");
    }
}
