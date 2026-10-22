package com.urlshortener.pipeline;

import com.urlshortener.dto.ClickEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production Redis Streams consumer with Consumer Groups (XREADGROUP),
 * explicit post-commit XACK, and idle pending-entry recovery (XPENDING / XCLAIM).
 */
@Component("redisStreamClickConsumer")
@ConditionalOnProperty(name = "app.queue.impl", havingValue = "redis", matchIfMissing = true)
public class RedisStreamClickConsumer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamClickConsumer.class);

    private final StringRedisTemplate redisTemplate;
    private final ClickEventProcessor processor;

    private final String streamKey;
    private final String groupName;
    private final String consumerName;
    private final int batchSize;
    private final long pollTimeoutMs;
    private final long idleThresholdMs;
    private final boolean autoStart;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong ackedCount = new AtomicLong(0);
    private final AtomicLong recoveredPendingCount = new AtomicLong(0);

    private ExecutorService consumerExecutor;
    private ScheduledExecutorService recoveryExecutor;

    // Hook for simulating consumer crash mid-batch before XACK in durability testing
    private volatile boolean simulateCrashMidBatch = false;
    private volatile int crashAfterProcessingN = -1;
    private final AtomicLong currentBatchProcessed = new AtomicLong(0);

    @Autowired
    public RedisStreamClickConsumer(
            StringRedisTemplate redisTemplate,
            ClickEventProcessor processor,
            @Value("${app.queue.stream-key:clicks:stream}") String streamKey,
            @Value("${app.queue.group-name:clicks:consumer-group}") String groupName,
            @Value("${app.queue.consumer-name:worker-1}") String consumerName,
            @Value("${app.queue.batch-size:50}") int batchSize,
            @Value("${app.queue.poll-timeout-ms:1000}") long pollTimeoutMs,
            @Value("${app.queue.idle-threshold-ms:60000}") long idleThresholdMs,
            @Value("${app.consumer.enabled:true}") boolean autoStart,
            @Autowired(required = false) io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.processor = processor;
        this.streamKey = streamKey;
        this.groupName = groupName;
        this.consumerName = consumerName;
        this.batchSize = batchSize;
        this.pollTimeoutMs = pollTimeoutMs;
        this.idleThresholdMs = idleThresholdMs;
        this.autoStart = autoStart;

        if (meterRegistry != null) {
            meterRegistry.gauge("click.events.acked", ackedCount);
            meterRegistry.gauge("click.events.recovered_pending", recoveredPendingCount);
            meterRegistry.gauge("click.stream.queue_depth", this, RedisStreamClickConsumer::getQueueDepth);
        }
    }

    public RedisStreamClickConsumer(
            StringRedisTemplate redisTemplate,
            ClickEventProcessor processor,
            String streamKey,
            String groupName,
            String consumerName,
            int batchSize,
            long pollTimeoutMs,
            long idleThresholdMs,
            boolean autoStart) {
        this(redisTemplate, processor, streamKey, groupName, consumerName, batchSize, pollTimeoutMs, idleThresholdMs, autoStart, null);
    }

    @PostConstruct
    public void start() {
        if (!autoStart) {
            log.info("RedisStreamClickConsumer auto-start is disabled (producer-only node or test).");
            return;
        }
        initConsumerGroup();
        if (running.compareAndSet(false, true)) {
            consumerExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "redis-stream-consumer-" + consumerName);
                t.setDaemon(true);
                return t;
            });
            consumerExecutor.submit(this);

            // Periodic pending-entry recovery daemon
            recoveryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "redis-pending-recovery-" + consumerName);
                t.setDaemon(true);
                return t;
            });
            recoveryExecutor.scheduleWithFixedDelay(this::recoverPendingEntries, 5, 15, TimeUnit.SECONDS);

            log.info("RedisStreamClickConsumer started for group '{}', consumer '{}'", groupName, consumerName);
        }
    }

    public void initConsumerGroup() {
        try {
            redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
                try {
                    connection.streamCommands().xGroupCreate(
                        streamKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        groupName,
                        ReadOffset.from("0"),
                        true
                    );
                    log.info("Initialized consumer group '{}' on stream '{}' with MKSTREAM", groupName, streamKey);
                } catch (Exception e) {
                    // Group already exists (BUSYGROUP)
                    log.debug("Consumer group '{}' already exists: {}", groupName, e.getMessage());
                }
                return null;
            });
        } catch (Exception e) {
            log.debug("Consumer group initialization deferred: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (consumerExecutor != null) {
            consumerExecutor.shutdownNow();
        }
        if (recoveryExecutor != null) {
            recoveryExecutor.shutdownNow();
        }
        log.info("RedisStreamClickConsumer stopped.");
    }

    @Override
    public void run() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                int count = pollAndProcess();
                if (count == 0) {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in RedisStreamClickConsumer loop: {}", e.getMessage());
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Reads a batch of new messages with XREADGROUP, processes each idempotently,
     * and performs explicit XACK only after the transaction commits.
     */
    public int pollAndProcess() throws InterruptedException {
        try {
            // Non-blocking stream read prevents Lettuce command timeout on idle streams
            StreamReadOptions readOpts = StreamReadOptions.empty().count(batchSize);
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(groupName, consumerName),
                readOpts,
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()) // ">"
            );

            if (records == null || records.isEmpty()) {
                return 0;
            }

            int processedInBatch = 0;
            for (MapRecord<String, Object, Object> record : records) {
                if (simulateCrashMidBatch && crashAfterProcessingN > 0 && currentBatchProcessed.get() >= crashAfterProcessingN) {
                    log.warn("SIMULATED CONSUMER CRASH mid-batch! Dropping thread before acknowledging record {}", record.getId());
                    running.set(false);
                    throw new RuntimeException("Simulated crash mid-batch before XACK for durability drill");
                }

                ClickEvent event = parseRecord(record);
                if (event.traceId() != null && !event.traceId().isBlank()) {
                    org.slf4j.MDC.put("traceId", event.traceId());
                }
                // Step 1 & 2 & 3: Durable dedup, Anomaly check, Rollup commit in DB transaction
                boolean processed;
                try {
                    processed = processor.process(event, consumerName);
                } finally {
                    org.slf4j.MDC.remove("traceId");
                }

                // Step 4: Explicit XACK ONLY AFTER transaction commits
                redisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());
                ackedCount.incrementAndGet();
                processedInBatch++;
                currentBatchProcessed.incrementAndGet();
            }
            return processedInBatch;
        } catch (Exception e) {
            if (simulateCrashMidBatch) {
                throw new RuntimeException(e);
            }
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            Throwable cause = e.getCause();
            if (cause != null && cause.getMessage() != null) {
                msg = msg + " (Cause: " + cause.getMessage() + ")";
            }
            String msgLower = msg.toLowerCase();
            if (msgLower.contains("nogroup") || msgLower.contains("no such key") || msgLower.contains("err") || msgLower.contains("execution")) {
                initConsumerGroup();
            }
            Thread.sleep(500);
            return 0;
        }
    }

    /**
     * Recovers pending unacknowledged entries idle beyond threshold (XPENDING & XCLAIM).
     */
    public int recoverPendingEntries() {
        try {
            PendingMessages pendingMessages = redisTemplate.opsForStream().pending(
                streamKey,
                groupName,
                Range.unbounded(),
                batchSize
            );

            if (pendingMessages == null || pendingMessages.isEmpty()) {
                return 0;
            }

            int recovered = 0;
            for (PendingMessage pending : pendingMessages) {
                long idleTime = pending.getElapsedTimeSinceLastDelivery() != null
                    ? pending.getElapsedTimeSinceLastDelivery().toMillis()
                    : 0L;
                if (idleTime >= idleThresholdMs) {
                    RecordId id = pending.getId();
                    log.info("Recovering idle pending message {} (idle {}ms >= {}ms)", id, idleTime, idleThresholdMs);

                    List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream().range(
                        streamKey,
                        Range.just(id.getValue())
                    );

                    if (claimed != null && !claimed.isEmpty()) {
                        for (MapRecord<String, Object, Object> record : claimed) {
                            ClickEvent event = parseRecord(record);
                            if (event.traceId() != null && !event.traceId().isBlank()) {
                                org.slf4j.MDC.put("traceId", event.traceId());
                            }
                            try {
                                processor.process(event, consumerName + "-recovery");
                            } finally {
                                org.slf4j.MDC.remove("traceId");
                            }
                            // Acknowledge claimed record
                            redisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());
                            ackedCount.incrementAndGet();
                            recovered++;
                            recoveredPendingCount.incrementAndGet();
                        }
                    }
                }
            }
            return recovered;
        } catch (Exception e) {
            log.debug("Pending recovery check: {}", e.getMessage());
            return 0;
        }
    }

    public long getQueueDepth() {
        try {
            Long size = redisTemplate.opsForStream().size(streamKey);
            return size != null ? size : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private ClickEvent parseRecord(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        String eventId = (String) value.get("eventId");
        String shortCode = (String) value.get("shortCode");
        String urlIdStr = (String) value.get("urlId");
        Long urlId = urlIdStr != null && !urlIdStr.isEmpty() ? Long.parseLong(urlIdStr) : 0L;
        String timestampStr = (String) value.get("timestamp");
        LocalDateTime timestamp = timestampStr != null && !timestampStr.isEmpty()
            ? LocalDateTime.parse(timestampStr)
            : LocalDateTime.now();
        String ip = (String) value.get("ip");
        String userAgent = (String) value.get("userAgent");
        String referrer = (String) value.get("referrer");
        String traceId = (String) value.get("traceId");

        return new ClickEvent(eventId, shortCode, urlId, timestamp, ip, userAgent, referrer, traceId);
    }

    public void enableCrashSimulation(int crashAfterN) {
        this.simulateCrashMidBatch = true;
        this.crashAfterProcessingN = crashAfterN;
        this.currentBatchProcessed.set(0);
    }

    public void disableCrashSimulation() {
        this.simulateCrashMidBatch = false;
        this.crashAfterProcessingN = -1;
    }

    public long getAckedCount() {
        return ackedCount.get();
    }

    public long getRecoveredPendingCount() {
        return recoveredPendingCount.get();
    }

    public boolean isRunning() {
        return running.get();
    }
}
