package com.urlshortener.pipeline;

import com.urlshortener.dto.ClickEvent;

/**
 * Event ingestion channel interface decoupling redirect execution from analytical ingestion.
 */
public interface ClickEventChannel {

    /**
     * Non-blocking publish to the ingestion channel.
     * Guaranteed O(1) response time without delaying HTTP 302 redirects.
     */
    boolean publish(ClickEvent event);

    /**
     * Current approximate backlog depth.
     */
    int size();

    /**
     * Cumulative count of successfully published events.
     */
    long getPublishedCount();

    /**
     * Cumulative count of dropped/rejected events under backpressure.
     */
    long getDroppedCount();

    /**
     * Clear buffer (primarily for test resets).
     */
    default void clear() {}
}
