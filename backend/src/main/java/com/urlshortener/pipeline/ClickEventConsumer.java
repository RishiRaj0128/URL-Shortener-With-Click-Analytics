package com.urlshortener.pipeline;

import com.urlshortener.dto.ClickEvent;
import com.urlshortener.entity.ClickEventDedup;
import com.urlshortener.entity.ClickRollup;
import com.urlshortener.repository.ClickEventDedupRepository;
import com.urlshortener.repository.ClickRollupRepository;
import com.urlshortener.service.AnomalyDetector;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory background consumer worker consuming from InMemoryClickEventChannel.
 * Enabled when app.queue.impl=memory.
 */
@Component("inMemoryClickEventConsumer")
@ConditionalOnProperty(name = "app.queue.impl", havingValue = "memory")
public class ClickEventConsumer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ClickEventConsumer.class);

    private final ClickEventChannel channel;
    private final ClickRollupRepository rollupRepository;
    private final ClickEventDedupRepository dedupRepository;
    private final AnomalyDetector anomalyDetector;
    private ClickEventProcessor clickEventProcessor;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong duplicateCount = new AtomicLong(0);
    private final AtomicLong botEventCount = new AtomicLong(0);

    // Fast in-memory deduplication cache in addition to persistent DB table
    private final Set<String> localDedupCache = Collections.newSetFromMap(
        new ConcurrentHashMap<String, Boolean>()
    );

    private ExecutorService consumerExecutor;

    public ClickEventConsumer(
            ClickEventChannel channel,
            ClickRollupRepository rollupRepository,
            ClickEventDedupRepository dedupRepository,
            AnomalyDetector anomalyDetector) {
        this.channel = channel;
        this.rollupRepository = rollupRepository;
        this.dedupRepository = dedupRepository;
        this.anomalyDetector = anomalyDetector;
    }

    @Autowired
    public void setClickEventProcessor(ClickEventProcessor clickEventProcessor) {
        this.clickEventProcessor = clickEventProcessor;
    }

    @PostConstruct
    public void start() {
        if (running.compareAndSet(false, true)) {
            consumerExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "click-event-consumer");
                t.setDaemon(true);
                return t;
            });
            consumerExecutor.submit(this);
            log.info("ClickEventConsumer background pipeline started.");
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (consumerExecutor != null) {
            consumerExecutor.shutdownNow();
        }
        log.info("ClickEventConsumer background pipeline stopped.");
    }

    @Override
    public void run() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                ClickEvent event = null;
                if (channel instanceof InMemoryClickEventChannel inMemoryChannel) {
                    event = inMemoryChannel.poll(200, TimeUnit.MILLISECONDS);
                } else {
                    Thread.sleep(200);
                }
                if (event != null) {
                    processEvent(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Unexpected error in ClickEventConsumer loop: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Idempotently process an individual ClickEvent.
     */
    @Transactional
    public boolean processEvent(ClickEvent event) {
        if (clickEventProcessor != null) {
            boolean result = clickEventProcessor.process(event, "in-memory-consumer");
            if (result) {
                processedCount.incrementAndGet();
            } else {
                duplicateCount.incrementAndGet();
            }
            return result;
        }

        // Fallback for standalone unit tests without Spring context
        if (isDuplicate(event.eventId())) {
            duplicateCount.incrementAndGet();
            log.debug("Idempotency match: Duplicate event {} dropped.", event.eventId());
            return false;
        }

        recordDeduplication(event.eventId(), event.shortCode());

        AnomalyDetector.AnomalyResult velocityCheck = anomalyDetector != null
            ? anomalyDetector.evaluateClickVelocity(event)
            : new AnomalyDetector.AnomalyResult(false, null, null, 0.0);

        boolean isBot = velocityCheck.isAnomaly();
        if (isBot) {
            botEventCount.incrementAndGet();
        }

        LocalDateTime eventTime = event.timestamp() != null ? event.timestamp() : LocalDateTime.now();
        LocalDateTime windowStart = eventTime.truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime windowEnd = windowStart.plusMinutes(1);

        if (rollupRepository != null) {
            ClickRollup rollup = rollupRepository.findByShortCodeAndWindowStart(event.shortCode(), windowStart)
                .orElseGet(() -> new ClickRollup(event.shortCode(), windowStart, windowEnd));

            if (isBot) {
                rollup.incrementBotClicks();
            } else {
                rollup.incrementLegitimateClicks();
            }

            if (anomalyDetector != null && !isBot) {
                AnomalyDetector.AnomalyResult zScoreCheck = anomalyDetector.evaluateBucketZScore(
                    event.shortCode(),
                    rollup.getLegitimateClicks()
                );
                if (zScoreCheck.isAnomaly()) {
                    rollup.setIsAnomaly(true);
                    rollup.setAnomalyReason(zScoreCheck.details());
                    rollup.setZScore(zScoreCheck.zScore());
                }
            }

            rollupRepository.save(rollup);
        }

        processedCount.incrementAndGet();
        return true;
    }

    private boolean isDuplicate(String eventId) {
        if (localDedupCache.contains(eventId)) {
            return true;
        }
        if (dedupRepository != null && dedupRepository.existsById(eventId)) {
            localDedupCache.add(eventId);
            return true;
        }
        return false;
    }

    private void recordDeduplication(String eventId, String shortCode) {
        localDedupCache.add(eventId);
        if (dedupRepository != null) {
            try {
                dedupRepository.save(new ClickEventDedup(eventId, shortCode));
            } catch (Exception e) {
                log.warn("Dedup record save failed (possibly concurrent insert): {}", e.getMessage());
            }
        }
    }

    public long getProcessedCount() {
        return processedCount.get();
    }

    public long getDuplicateCount() {
        return duplicateCount.get();
    }

    public long getBotEventCount() {
        return botEventCount.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    public void clearLocalCache() {
        localDedupCache.clear();
    }
}
