package com.urlshortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configuration for asynchronous task execution.
 * 
 * Used to log clicks and fetch geolocation data asynchronously,
 * ensuring the redirect response is sent immediately without waiting
 * for blocking operations like external API calls.
 */
@Configuration
public class AsyncConfiguration {
    
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);           // Number of threads kept alive
        executor.setMaxPoolSize(10);           // Maximum threads in the pool
        executor.setQueueCapacity(100);        // Queue size for tasks
        executor.setThreadNamePrefix("async-"); // Thread name prefix for debugging
        executor.initialize();
        return executor;
    }
}
