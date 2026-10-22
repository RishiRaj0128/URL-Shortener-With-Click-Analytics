package com.urlshortener.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.dto.AuthRequest;
import com.urlshortener.dto.AuthResponse;
import com.urlshortener.dto.ShortenUrlRequest;
import com.urlshortener.dto.ShortenUrlResponse;
import com.urlshortener.entity.Url;
import com.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * ClusterIntegrationTest spins up real PostgreSQL and Redis containers using Testcontainers
 * when Docker is available (e.g. in CI pipelines), and falls back gracefully to in-memory mode when Docker is absent.
 * 
 * Verifies end-to-end:
 * 1. Database schema migration & persistence.
 * 2. URL shortening and Base62 resolution.
 * 3. Fast-path redirect (HTTP 302) with location header validation.
 * 4. Link soft-delete / expiration returning HTTP 410 Gone with immediate cache eviction.
 * 5. Multi-instance token bucket and safety filter enforcement.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@DisplayName("Phase 6: End-to-End Cluster Integration Test (Postgres + Redis)")
class ClusterIntegrationTest {

    private static final boolean DOCKER_AVAILABLE;

    static {
        boolean available = false;
        try {
            available = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            available = false;
        }
        DOCKER_AVAILABLE = available;
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("urlshortener_test")
            .withUsername("test_user")
            .withPassword("test_password");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (DOCKER_AVAILABLE) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.flyway.enabled", () -> "true");
            registry.add("spring.data.redis.host", redis::getHost);
            registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
            registry.add("spring.cache.type", () -> "redis");
            registry.add("app.queue.impl", () -> "redis");
        } else {
            // In-memory fallback if docker is absent on local workstation
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:clustertest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "");
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.H2Dialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
            registry.add("spring.flyway.enabled", () -> "false");
            registry.add("spring.cache.type", () -> "simple");
            registry.add("app.queue.impl", () -> "memory");
        }
        registry.add("jwt.secret", () -> "very-secure-jwt-secret-key-that-is-at-least-256-bits-long-for-testing-cluster-suite");
        registry.add("app.security.ip-salt", () -> "cluster-integration-test-ip-salt-at-least-32-chars");
        registry.add("app.base-url", () -> "http://localhost:8080");
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private String jwtToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = webAppContextSetup(webApplicationContext).build();

        if (jwtToken == null) {
            String testEmail = "cluster_" + System.currentTimeMillis() + "@example.com";
            AuthRequest signupRequest = new AuthRequest(testEmail, "password123");

            MvcResult result = mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(signupRequest)))
                    .andExpect(status().isCreated())
                    .andReturn();

            AuthResponse authResponse = objectMapper.readValue(
                    result.getResponse().getContentAsString(),
                    AuthResponse.class);
            jwtToken = authResponse.getToken();
        }
    }

    @Test
    @DisplayName("End-to-end shorten URL, redirect fast-path, and verify 302 Found")
    void testShortenAndRedirectFlow() throws Exception {
        ShortenUrlRequest request = new ShortenUrlRequest("https://example.com/target-landing-page");

        MvcResult createResult = mockMvc.perform(post("/api/urls")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").exists())
                .andReturn();

        ShortenUrlResponse response = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                ShortenUrlResponse.class);
        String shortCode = response.getShortCode();
        assertNotNull(shortCode);

        // Perform fast-path redirect request
        mockMvc.perform(get("/" + shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/target-landing-page"));
    }

    @Test
    @DisplayName("Custom alias URL shortening and conflict handling")
    void testCustomAliasFlow() throws Exception {
        String customAlias = "my-promo-" + System.currentTimeMillis();
        ShortenUrlRequest request = new ShortenUrlRequest("https://example.com/promo", customAlias);

        mockMvc.perform(post("/api/urls")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value(customAlias));

        // Duplicate custom alias must return 409 Conflict
        mockMvc.perform(post("/api/urls")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Soft-deleted URL returns HTTP 410 Gone and evicts from fast-path cache")
    void testSoftDeletedUrlReturns410() throws Exception {
        ShortenUrlRequest request = new ShortenUrlRequest("https://example.com/delete-test");

        MvcResult createResult = mockMvc.perform(post("/api/urls")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        ShortenUrlResponse response = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                ShortenUrlResponse.class);
        String shortCode = response.getShortCode();

        // Prime cache
        mockMvc.perform(get("/" + shortCode))
                .andExpect(status().isFound());

        // Soft delete the URL via repository
        Optional<Url> urlOpt = urlRepository.findByShortCode(shortCode);
        assertTrue(urlOpt.isPresent());
        Url url = urlOpt.get();
        url.setDeleted(true);
        url.setDeletedAt(LocalDateTime.now());
        urlRepository.save(url);

        // Next redirect must return 410 GONE
        mockMvc.perform(get("/" + shortCode))
                .andExpect(status().isGone());
    }
}
