package com.urlshortener.service;

import com.urlshortener.dto.ClickEvent;
import com.urlshortener.entity.ClickAnomalyLog;
import com.urlshortener.repository.ClickAnomalyLogRepository;
import com.urlshortener.util.IpHashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hardened Anomaly Detection Engine for Traffic Intelligence.
 * 
 * Resilience & Robustness Features:
 * 1. IP Velocity Heuristic: Sliding window rate limiting with quarantine audit log.
 * 2. Robust Z-Score via Median + MAD (Median Absolute Deviation):
 *    Robust Z = 0.6745 * (x - median) / MAD
 *    Resistant to outlier skew and distribution distortion.
 * 3. Poisoning Resistance: Flagged spike buckets are excluded from the rolling baseline
 *    so attacks cannot "learn themselves into normal".
 * 4. Division-by-Zero Floor: Explicit floor on MAD (madFloor >= 1.0) handles flat/constant series.
 * 5. Cold-Start Guard: Suppresses statistical verdicts until a minimum sample count
 *    (default: 30 buckets) is accumulated (never false flags on uninitialized links).
 * 6. Step-Change Re-baselining: Sustained new traffic levels persisting across a full
 *    observation window (30 buckets) are recognized as legitimate shifts and re-baselined.
 */
@Service
public class AnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);

    @Value("${app.anomaly.velocity-window-seconds:10}")
    private int velocityWindowSeconds = 10;

    @Value("${app.anomaly.velocity-max-requests:15}")
    private int velocityMaxRequests = 15;

    @Value("${app.anomaly.zscore-threshold:3.0}")
    private double zScoreThreshold = 3.0;

    @Value("${app.anomaly.min-spike-volume:20}")
    private int minSpikeVolume = 20;

    @Value("${app.anomaly.min-sample-count:30}")
    private int minSampleCount = 30;

    @Value("${app.anomaly.mad-floor:1.0}")
    private double madFloor = 1.0;

    @Value("${app.anomaly.step-change-rebaseline-buckets:30}")
    private int stepChangeRebaselineBuckets = 30;

    private final ClickAnomalyLogRepository anomalyLogRepository;
    private final com.urlshortener.anomaly.ClickFeatureExtractor featureExtractor;
    private final com.urlshortener.anomaly.ExplainableAnomalyClassifier explainableClassifier;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    // Sliding window of timestamps per "ipHash:shortCode" (local fallback)
    private final Map<String, Deque<Long>> velocityTracker = new ConcurrentHashMap<>();

    // Historical clean window counts per shortCode for robust Z-score
    private final Map<String, Deque<Long>> historyTracker = new ConcurrentHashMap<>();

    // Tracks consecutive elevated buckets for legitimate step-change re-baselining
    private final Map<String, AtomicInteger> consecutiveElevatedTracker = new ConcurrentHashMap<>();

    public AnomalyDetector(ClickAnomalyLogRepository anomalyLogRepository) {
        this(anomalyLogRepository, new com.urlshortener.anomaly.ClickFeatureExtractor(), new com.urlshortener.anomaly.ExplainableAnomalyClassifier(), null);
    }

    public AnomalyDetector(
            ClickAnomalyLogRepository anomalyLogRepository,
            com.urlshortener.anomaly.ClickFeatureExtractor featureExtractor,
            com.urlshortener.anomaly.ExplainableAnomalyClassifier explainableClassifier) {
        this(anomalyLogRepository, featureExtractor, explainableClassifier, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AnomalyDetector(
            ClickAnomalyLogRepository anomalyLogRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.urlshortener.anomaly.ClickFeatureExtractor featureExtractor,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.urlshortener.anomaly.ExplainableAnomalyClassifier explainableClassifier,
            @org.springframework.beans.factory.annotation.Autowired(required = false) org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
        this.anomalyLogRepository = anomalyLogRepository;
        this.featureExtractor = featureExtractor != null ? featureExtractor : new com.urlshortener.anomaly.ClickFeatureExtractor();
        this.explainableClassifier = explainableClassifier != null ? explainableClassifier : new com.urlshortener.anomaly.ExplainableAnomalyClassifier();
        this.redisTemplate = redisTemplate;
    }

    public com.urlshortener.anomaly.ClickFeatureExtractor getFeatureExtractor() {
        return featureExtractor;
    }

    public com.urlshortener.anomaly.ExplainableAnomalyClassifier getExplainableClassifier() {
        return explainableClassifier;
    }

    /**
     * Evaluates a full multi-dimensional window combining IP entropy, user-agent entropy,
     * inter-arrival jitter, velocity, and robust Z-score into an explainable classification.
     */
    public com.urlshortener.anomaly.ExplainableAnomalyClassifier.ClassificationResult evaluateWindowMultiDimensional(
            String shortCode,
            List<com.urlshortener.anomaly.ClickFeatureExtractor.ClickSample> samples,
            long currentBucketCount) {
        AnomalyResult zResult = evaluateBucketZScore(shortCode, currentBucketCount);
        com.urlshortener.anomaly.ClickFeatureExtractor.ClickFeatureVector vector = featureExtractor.extractFeatures(
            shortCode,
            samples,
            zResult.zScore()
        );
        return explainableClassifier.classify(vector);
    }

    /**
     * Evaluates incoming click event against the velocity heuristic.
     * Flagged events are quarantined and persisted to the audit trail.
     */
    public AnomalyResult evaluateClickVelocity(ClickEvent event) {
        String clientIp = event.clientIp() != null ? event.clientIp() : "unknown";
        String ipHash = IpHashUtil.hashIp(clientIp);
        String trackerKey = ipHash + ":" + event.shortCode();

        long nowMillis = System.currentTimeMillis();
        long windowStartMillis = nowMillis - (velocityWindowSeconds * 1000L);

        int currentVelocity;
        if (redisTemplate != null) {
            try {
                String redisKey = "velocity:" + trackerKey;
                String uniqueMember = nowMillis + ":" + UUID.randomUUID().toString().substring(0, 8);
                redisTemplate.opsForZSet().removeRangeByScore(redisKey, 0, (double) windowStartMillis);
                redisTemplate.opsForZSet().add(redisKey, uniqueMember, (double) nowMillis);
                Long size = redisTemplate.opsForZSet().size(redisKey);
                redisTemplate.expire(redisKey, java.time.Duration.ofSeconds(velocityWindowSeconds * 2L));
                currentVelocity = (size != null) ? size.intValue() : 1;
            } catch (Exception e) {
                log.warn("Redis velocity check failed, falling back to local deque: {}", e.getMessage());
                currentVelocity = evaluateLocalVelocity(trackerKey, nowMillis, windowStartMillis);
            }
        } else {
            currentVelocity = evaluateLocalVelocity(trackerKey, nowMillis, windowStartMillis);
        }

        if (currentVelocity > velocityMaxRequests) {
            String details = String.format(
                "Velocity breach: %d requests in %ds window (threshold: %d) from IP hash %s",
                currentVelocity, velocityWindowSeconds, velocityMaxRequests, ipHash
            );
            log.warn("Anomaly detected: BOT_BURST_VELOCITY for shortCode {} - {}", event.shortCode(), details);

            if (anomalyLogRepository != null) {
                try {
                    ClickAnomalyLog auditEntry = new ClickAnomalyLog(
                        event.shortCode(),
                        ipHash,
                        "BOT_BURST_VELOCITY",
                        details
                    );
                    anomalyLogRepository.save(auditEntry);
                } catch (Exception e) {
                    log.error("Failed to persist anomaly audit log: {}", e.getMessage());
                }
            }

            return new AnomalyResult(true, "BOT_BURST_VELOCITY", details, 0.0);
        }

        return new AnomalyResult(false, null, null, 0.0);
    }

    private int evaluateLocalVelocity(String trackerKey, long nowMillis, long windowStartMillis) {
        Deque<Long> timestamps = velocityTracker.computeIfAbsent(
            trackerKey,
            k -> new ConcurrentLinkedDeque<>()
        );

        while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStartMillis) {
            timestamps.pollFirst();
        }

        timestamps.addLast(nowMillis);
        return timestamps.size();
    }

    /**
     * Evaluates a time bucket count against historical rolling distribution using Robust Z-Score (Median + MAD).
     * Enforces cold-start protection, poisoning resistance, and division-by-zero floor.
     */
    public AnomalyResult evaluateBucketZScore(String shortCode, long currentBucketCount) {
        Deque<Long> history = historyTracker.computeIfAbsent(
            shortCode,
            k -> new ConcurrentLinkedDeque<>()
        );

        AtomicInteger elevatedCounter = consecutiveElevatedTracker.computeIfAbsent(
            shortCode,
            k -> new AtomicInteger(0)
        );

        // Guard 1: Cold-Start Protection (never emit false positive before sufficient samples)
        if (history.size() < minSampleCount) {
            history.addLast(currentBucketCount);
            String reason = String.format("Cold start: %d/%d baseline samples accumulated (insufficient data for statistical verdict)",
                history.size(), minSampleCount);
            return new AnomalyResult(false, "INSUFFICIENT_DATA", reason, 0.0);
        }

        // Guard 2: Robust Median + MAD Calculation
        List<Double> sampleList = new ArrayList<>();
        for (Long v : history) {
            sampleList.add(v.doubleValue());
        }

        double median = computeMedian(sampleList);
        double mad = computeMad(sampleList, median);

        // Robust Z-score: 0.6745 * (x - median) / MAD
        double robustZ = 0.6745 * (currentBucketCount - median) / mad;

        boolean isSpike = false;
        String reason = null;

        if (robustZ >= zScoreThreshold && currentBucketCount >= minSpikeVolume) {
            isSpike = true;
            int elevatedConsecutive = elevatedCounter.incrementAndGet();

            // Guard 3: Step-Change Re-baselining
            // If elevated traffic remains continuous across a full window (e.g. 30 buckets),
            // recognize as permanent legitimate step change and re-baseline.
            if (elevatedConsecutive >= stepChangeRebaselineBuckets) {
                log.info("Sustained traffic elevation across {} consecutive buckets for shortCode {}. Re-baselining established.",
                    elevatedConsecutive, shortCode);
                history.clear();
                for (int i = 0; i < minSampleCount; i++) {
                    history.addLast(currentBucketCount);
                }
                elevatedCounter.set(0);
                isSpike = false;
                reason = "Legitimate step-change re-baselined to " + currentBucketCount;
            } else {
                reason = String.format("Robust Z-Score spike: Z=%.2f >= %.2f (median=%.1f, MAD=%.1f, count=%d)",
                    robustZ, zScoreThreshold, median, mad, currentBucketCount);
                log.info("Anomaly detected: STATISTICAL_VOLUME_SPIKE for shortCode {} - {}", shortCode, reason);
            }
        } else {
            elevatedCounter.set(0);
        }

        // Guard 4: Poisoning Resistance
        // Quarantined spike buckets are NOT added to baseline to prevent malicious inflation of normal range.
        if (!isSpike) {
            history.addLast(currentBucketCount);
            while (history.size() > Math.max(minSampleCount, 30)) {
                history.pollFirst();
            }
        } else {
            log.debug("Quarantining bucket count {} from shortCode {} baseline to prevent model poisoning.",
                currentBucketCount, shortCode);
        }

        return new AnomalyResult(isSpike, isSpike ? "STATISTICAL_VOLUME_SPIKE" : null, reason, robustZ);
    }

    private double computeMedian(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        } else {
            return (sorted.get((n / 2) - 1) + sorted.get(n / 2)) / 2.0;
        }
    }

    private double computeMad(List<Double> values, double median) {
        List<Double> absDeviations = new ArrayList<>(values.size());
        for (Double v : values) {
            absDeviations.add(Math.abs(v - median));
        }
        double rawMad = computeMedian(absDeviations);
        // Explicit floor to handle constant series (flat traffic) and prevent division by zero
        return Math.max(rawMad, madFloor);
    }

    public void setVelocityParameters(int windowSeconds, int maxRequests) {
        this.velocityWindowSeconds = windowSeconds;
        this.velocityMaxRequests = maxRequests;
    }

    public void setZScoreThreshold(double zScoreThreshold) {
        this.zScoreThreshold = zScoreThreshold;
    }

    public void setMinSpikeVolume(int minSpikeVolume) {
        this.minSpikeVolume = minSpikeVolume;
    }

    public void setMinSampleCount(int minSampleCount) {
        this.minSampleCount = minSampleCount;
    }

    public void setMadFloor(double madFloor) {
        this.madFloor = madFloor;
    }

    public void setStepChangeRebaselineBuckets(int buckets) {
        this.stepChangeRebaselineBuckets = buckets;
    }

    public void clear() {
        velocityTracker.clear();
        historyTracker.clear();
        consecutiveElevatedTracker.clear();
    }

    public record AnomalyResult(
        boolean isAnomaly,
        String anomalyType,
        String details,
        double zScore
    ) {}
}
