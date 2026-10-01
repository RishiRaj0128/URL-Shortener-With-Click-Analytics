package com.urlshortener.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis cache configuration using cache-aside pattern.
 * 
 * CACHING STRATEGY:
 * - Pattern: Cache-Aside (Lazy Loading)
 * 1. On redirect (GET /{shortCode}), check Redis first
 * 2. Cache miss → query PostgreSQL via JPA
 * 3. On cache miss, populate Redis with 1-hour TTL
 * 
 * - TTL: 1 hour (3600 seconds)
 * Short-to-medium TTL balances memory usage against staleness.
 * If URL is deleted, Redis entry expires after 1 hour.
 * 
 * - Invalidation:
 * Manual via @CacheEvict on URL updates/deletions
 * 
 * - Trade-offs:
 * ✓ Avoids cache stampede (lazy loading)
 * ✓ Reduces database load on read-heavy redirects
 * ✗ First request after cache miss pays DB latency
 * ✗ Stale reads possible for 1 hour after URL update
 */
@Configuration
@EnableCaching
public class CacheConfiguration {

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
}
