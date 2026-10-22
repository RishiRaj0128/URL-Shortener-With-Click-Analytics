package com.urlshortener.pipeline;

import com.urlshortener.dto.ClickEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production Redis Streams event ingestion channel.
 * 
 * Non-Blocking Decoupling Guarantee:
 * - Dispatches events asynchronously via a bounded thread pool.
 * - Under heavy load or Redis hiccups, fails fast without delaying the HTTP 302 redirect.
 * - Publishes using XADD with approximate max-length capping (MAXLEN ~ cap).
 */
@Component("redisStreamClickChannel")
@ConditionalOnProperty(name = "app.queue.impl", havingValue = "redis", matchIfMissing = true)
public class RedisStreamClickChannel implements ClickEventChannel {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamClickChannel.class);

    private final StringRedisTemplate redisTemplate;
    private final String streamKey;
    private final long maxStreamLength;
    private final ThreadPoolExecutor asyncPublisher;

    private final AtomicLong publishedCount = new AtomicLong(0);
    private final AtomicLong droppedCount = new AtomicLong(0);

    @Value("${app.queue.publish-enabled:true}")
    private boolean publishEnabled = true;

    @Autowired
    public RedisStreamClickChannel(
            StringRedisTemplate redisTemplate,
            @Value("${app.queue.stream-key:clicks:stream}") String streamKey,
            @Value("${app.queue.max-stream-length:50000}") long maxStreamLength,
            @Value("${app.queue.publisher-threads:4}") int publisherThreads,
            @Value("${app.queue.publisher-buffer-capacity:10000}") int bufferCapacity,
            @Autowired(required = false) io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
        this.maxStreamLength = maxStreamLength;

        if (meterRegistry != null) {
            meterRegistry.gauge("click.events.dropped", droppedCount);
            meterRegistry.gauge("click.events.published", publishedCount);
        }

        // Bounded thread pool: reject policy increments drop counter to protect HTTP threads
        this.asyncPublisher = new ThreadPoolExecutor(
            publisherThreads,
            publisherThreads,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(bufferCapacity),
            new ThreadFactory() {
                private final AtomicLong threadId = new AtomicLong(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "redis-stream-publisher-" + threadId.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            },
            (r, executor) -> {
                droppedCount.incrementAndGet();
                log.warn("RedisStreamClickChannel buffer full! Dropped event publish.");
            }
        );
    }

    /**
     * Test/mock constructor for unit testing with custom capacity.
     */
    public RedisStreamClickChannel(
            StringRedisTemplate redisTemplate,
            String streamKey,
            long maxStreamLength,
            int publisherThreads,
            int bufferCapacity) {
        this(redisTemplate, streamKey, maxStreamLength, publisherThreads, bufferCapacity, null);
    }

    /**
     * Test/mock constructor for unit testing.
     */
    public RedisStreamClickChannel(StringRedisTemplate redisTemplate, String streamKey, long maxStreamLength) {
        this(redisTemplate, streamKey, maxStreamLength, 2, 1000, null);
    }

    @Override
    public boolean publish(ClickEvent event) {
        if (!publishEnabled || event == null) {
            return false;
        }

        try {
            asyncPublisher.execute(() -> doPublish(event));
            return true;
        } catch (RejectedExecutionException e) {
            droppedCount.incrementAndGet();
            log.warn("RedisStreamClickChannel task rejected: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Executes the actual XADD with MAXLEN ~ cap command against Redis.
     */
    public RecordId doPublish(ClickEvent event) {
        try {
            Map<String, String> fields = new HashMap<>();
            fields.put("eventId", event.eventId() != null ? event.eventId() : "");
            fields.put("shortCode", event.shortCode() != null ? event.shortCode() : "");
            fields.put("urlId", event.urlId() != null ? String.valueOf(event.urlId()) : "0");
            fields.put("timestamp", event.timestamp() != null ? event.timestamp().toString() : "");
            fields.put("ip", event.clientIp() != null ? event.clientIp() : "");
            fields.put("userAgent", event.userAgent() != null ? event.userAgent() : "");
            fields.put("referrer", event.referrer() != null ? event.referrer() : "");
            fields.put("traceId", event.traceId() != null ? event.traceId() : "");

            // Native XADD with approximate max-length capping
            byte[] keyBytes = streamKey.getBytes(StandardCharsets.UTF_8);
            Map<byte[], byte[]> byteMap = new HashMap<>();
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                byteMap.put(entry.getKey().getBytes(StandardCharsets.UTF_8), entry.getValue().getBytes(StandardCharsets.UTF_8));
            }

            RecordId recordId = redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<RecordId>) connection -> {
                return connection.streamCommands().xAdd(
                    MapRecord.create(keyBytes, byteMap),
                    XAddOptions.maxlen(maxStreamLength).approximateTrimming(true)
                );
            });

            publishedCount.incrementAndGet();
            return recordId;
        } catch (Exception e) {
            droppedCount.incrementAndGet();
            log.warn("Failed to publish event {} to Redis stream {}: {}", event.eventId(), streamKey, e.getMessage());
            return null;
        }
    }

    @Override
    public int size() {
        try {
            Long len = redisTemplate.opsForStream().size(streamKey);
            return len != null ? len.intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public long getPublishedCount() {
        return publishedCount.get();
    }

    @Override
    public long getDroppedCount() {
        return droppedCount.get();
    }

    @Override
    public void clear() {
        try {
            redisTemplate.delete(streamKey);
        } catch (Exception ignored) {}
    }

    @PreDestroy
    public void shutdown() {
        asyncPublisher.shutdown();
        try {
            if (!asyncPublisher.awaitTermination(3, TimeUnit.SECONDS)) {
                asyncPublisher.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncPublisher.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
