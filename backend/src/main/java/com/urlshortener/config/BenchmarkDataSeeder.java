package com.urlshortener.config;

import com.urlshortener.entity.Url;
import com.urlshortener.entity.User;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Seeds benchmark URLs for reproducible k6 load testing suites.
 */
@Component
public class BenchmarkDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkDataSeeder.class);

    private final UrlRepository urlRepository;
    private final UserRepository userRepository;

    @Autowired
    public BenchmarkDataSeeder(UrlRepository urlRepository, UserRepository userRepository) {
        this.urlRepository = urlRepository;
        this.userRepository = userRepository;
    }

    @PostConstruct
    public void seedBenchmarkData() {
        try {
            User benchUser = userRepository.findByEmail("bench@juspay.in").orElseGet(() -> {
                User u = new User();
                u.setEmail("bench@juspay.in");
                u.setPasswordHash("$2a$10$abcdefghijklmnopqrstuv1234567890abcdefghijklmnopqrstuv");
                u.setCreatedAt(LocalDateTime.now());
                u.setUpdatedAt(LocalDateTime.now());
                return userRepository.save(u);
            });

            String[] warmCodes = {"bench_warm_1", "bench_warm_2", "bench_warm_3", "bench_cb_probe"};
            String[] targetUrls = {
                "https://juspay.in/products",
                "https://juspay.in/hyper-sdk",
                "https://juspay.in/developer",
                "https://juspay.in/resilience"
            };

            for (int i = 0; i < warmCodes.length; i++) {
                String code = warmCodes[i];
                if (urlRepository.findByShortCode(code).isEmpty()) {
                    Url url = new Url();
                    url.setUser(benchUser);
                    url.setShortCode(code);
                    url.setOriginalUrl(targetUrls[i]);
                    url.setCreatedAt(LocalDateTime.now());
                    url.setUpdatedAt(LocalDateTime.now());
                    urlRepository.save(url);
                    log.info("Seeded benchmark URL: {} -> {}", code, targetUrls[i]);
                }
            }
        } catch (Exception e) {
            log.debug("Benchmark data seeding skipped or already present: {}", e.getMessage());
        }
    }
}
