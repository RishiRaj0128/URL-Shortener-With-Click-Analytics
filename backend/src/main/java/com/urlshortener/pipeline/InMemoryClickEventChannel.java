package com.urlshortener.pipeline;

import com.urlshortener.dto.ClickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory bounded queue channel implementation.
 * Used for unit testing and local development (app.queue.impl=memory).
 */
@Component("inMemoryClickEventChannel")
@ConditionalOnProperty(name = "app.queue.impl", havingValue = "memory")
public class InMemoryClickEventChannel implements ClickEventChannel {

    private static final Logger log = LoggerFactory.getLogger(InMemoryClickEventChannel.class);
    private static final int DEFAULT_CAPACITY = 50_000;

    private final BlockingQueue<ClickEvent> queue;
    private final AtomicLong publishedCount = new AtomicLong(0);
    private final AtomicLong droppedCount = new AtomicLong(0);

    public InMemoryClickEventChannel(@Value("${app.pipeline.queue-capacity:50000}") int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity > 0 ? capacity : DEFAULT_CAPACITY);
    }

    @Override
    public boolean publish(ClickEvent event) {
        if (event == null) {
            return false;
        }
        boolean offered = queue.offer(event);
        if (offered) {
            publishedCount.incrementAndGet();
        } else {
            droppedCount.incrementAndGet();
            log.warn("InMemoryClickEventChannel queue full! Dropped event: {}", event.eventId());
        }
        return offered;
    }

    public ClickEvent take() throws InterruptedException {
        return queue.take();
    }

    public ClickEvent poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    @Override
    public int size() {
        return queue.size();
    }

    public int remainingCapacity() {
        return queue.remainingCapacity();
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
        queue.clear();
    }
}
