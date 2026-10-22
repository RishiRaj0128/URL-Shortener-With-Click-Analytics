package com.urlshortener.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfiguration implements CachingConfigurer {

  private static final Logger log = LoggerFactory.getLogger(CacheConfiguration.class);

  @Override
  public CacheErrorHandler errorHandler() {
    return new CacheErrorHandler() {
      @Override
      public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        // Suppress full 42-frame stack trace to prevent CPU exhaustion & log contention during Redis degradation
        log.warn("[CACHE FALLBACK] Redis GET failed for key '{}': {}. Transparent fallback to database.", key, exception.getMessage());
      }

      @Override
      public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn("[CACHE WARNING] Redis PUT failed for key '{}': {}", key, exception.getMessage());
      }

      @Override
      public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn("[CACHE WARNING] Redis EVICT failed for key '{}': {}", key, exception.getMessage());
      }

      @Override
      public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn("[CACHE WARNING] Redis CLEAR failed: {}", exception.getMessage());
      }
    };
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
  public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    mapper.activateDefaultTyping(
        com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator.instance,
        com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping.NON_FINAL,
        com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY
    );

    RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofHours(1)) // Default 1-hour TTL for all cache entries
        .serializeKeysWith(
            RedisSerializationContext.SerializationPair
                .fromSerializer(new StringRedisSerializer()))
        .serializeValuesWith(
            RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer(mapper)))
        .disableCachingNullValues(); // Don't cache null values to prevent issues

    return RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(config)
        .build();
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "spring.cache.type", havingValue = "simple")
  public org.springframework.cache.CacheManager simpleCacheManager() {
    return new org.springframework.cache.concurrent.ConcurrentMapCacheManager("urls");
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "spring.cache.type", havingValue = "none")
  public org.springframework.cache.CacheManager noOpCacheManager() {
    return new org.springframework.cache.support.NoOpCacheManager();
  }

  @org.springframework.beans.factory.annotation.Value("${spring.data.redis.host:localhost}")
  private String redisHost;

  @org.springframework.beans.factory.annotation.Value("${spring.data.redis.port:6379}")
  private int redisPort;

  @org.springframework.beans.factory.annotation.Value("${spring.data.redis.password:}")
  private String redisPassword;

  @org.springframework.beans.factory.annotation.Value("${app.redis.cache-timeout-ms:150}")
  private long cacheTimeoutMs;

  @org.springframework.beans.factory.annotation.Value("${app.redis.stream-timeout-ms:2000}")
  private long streamTimeoutMs;

  /**
   * Primary Lettuce connection factory for cache reads, rate limiting, and fast path operations.
   * Bound to 150ms command timeout (strictly below the Resilience4j 200ms slow-call threshold)
   * to guarantee fast failover to PostgreSQL without starving Tomcat request threads.
   */
  @Bean
  @org.springframework.context.annotation.Primary
  public org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory redisConnectionFactory() {
    org.springframework.data.redis.connection.RedisStandaloneConfiguration serverConfig = 
        new org.springframework.data.redis.connection.RedisStandaloneConfiguration(redisHost, redisPort);
    if (redisPassword != null && !redisPassword.isBlank()) {
      serverConfig.setPassword(redisPassword);
    }
    org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration clientConfig = 
        org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(cacheTimeoutMs))
            .clientOptions(io.lettuce.core.ClientOptions.builder()
                .timeoutOptions(io.lettuce.core.TimeoutOptions.enabled(Duration.ofMillis(cacheTimeoutMs)))
                .build())
            .build();
    return new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(serverConfig, clientConfig);
  }

  /**
   * Dedicated Lettuce connection factory for asynchronous streaming ingestion & consumer loop.
   * Uses a 2000ms timeout to accommodate bulk batch reading, pending message inspection,
   * and background processing without spurious Lettuce command timeouts.
   */
  @Bean("streamRedisConnectionFactory")
  public org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory streamRedisConnectionFactory() {
    org.springframework.data.redis.connection.RedisStandaloneConfiguration serverConfig = 
        new org.springframework.data.redis.connection.RedisStandaloneConfiguration(redisHost, redisPort);
    if (redisPassword != null && !redisPassword.isBlank()) {
      serverConfig.setPassword(redisPassword);
    }
    org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration clientConfig = 
        org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(streamTimeoutMs))
            .clientOptions(io.lettuce.core.ClientOptions.builder()
                .timeoutOptions(io.lettuce.core.TimeoutOptions.enabled(Duration.ofMillis(streamTimeoutMs)))
                .build())
            .build();
    return new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(serverConfig, clientConfig);
  }

  @Bean("streamRedisTemplate")
  public org.springframework.data.redis.core.StringRedisTemplate streamRedisTemplate(
      @org.springframework.beans.factory.annotation.Qualifier("streamRedisConnectionFactory") 
      org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory streamFactory) {
    return new org.springframework.data.redis.core.StringRedisTemplate(streamFactory);
  }
}
