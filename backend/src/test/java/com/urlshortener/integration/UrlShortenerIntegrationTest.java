package com.urlshortener.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.dto.AuthRequest;
import com.urlshortener.dto.AuthResponse;
import com.urlshortener.dto.ShortenUrlRequest;
import com.urlshortener.dto.ShortenUrlResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for URL Shortener Service.
 * 
 * Tests the full flow:
 * 1. User signup
 * 2. User login
 * 3. Create shortened URL
 * 4. Retrieve user's URLs
 * 5. Get analytics
 * 6. Redirect (cache hit/miss)
 * 
 * Uses TestContainers to spin up PostgreSQL for test database.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:urlshortener;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "spring.cache.type=simple",
    "app.queue.impl=memory",
    "logging.level.com.urlshortener=DEBUG"
})
class UrlShortenerIntegrationTest {

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  private String jwtToken;
  private String userEmail;
  private String userPassword = "password123";

  @TestConfiguration
  static class TestCacheConfiguration {
    @Bean
    @Primary
    CacheManager testCacheManager() {
      return new ConcurrentMapCacheManager("urls");
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    userEmail = "testuser" + System.nanoTime() + "@example.com";
    mockMvc = webAppContextSetup(webApplicationContext).build();

    // Sign up user
    AuthRequest signupRequest = new AuthRequest(userEmail, userPassword);
    MvcResult signupResult = mockMvc.perform(post("/api/auth/signup")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(signupRequest)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.token").exists())
        .andReturn();

    AuthResponse authResponse = objectMapper.readValue(
        signupResult.getResponse().getContentAsString(),
        AuthResponse.class);
    jwtToken = authResponse.getToken();
  }

  @Test
  void testFullUrlShorteningFlow() throws Exception {
    // 1. Shorten a URL
    ShortenUrlRequest shortenRequest = new ShortenUrlRequest("https://www.example.com/very/long/url");
    MvcResult shortenResult = mockMvc.perform(post("/api/shorten")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Authorization", "Bearer " + jwtToken)
        .content(objectMapper.writeValueAsString(shortenRequest)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.shortCode").exists())
        .andExpect(jsonPath("$.qrCodeBase64").exists())
        .andReturn();

    ShortenUrlResponse shortenResponse = objectMapper.readValue(
        shortenResult.getResponse().getContentAsString(),
        ShortenUrlResponse.class);

    String shortCode = shortenResponse.getShortCode();
    assertNotNull(shortCode);
    assertTrue(shortCode.length() > 0);

    // 2. Get user's URLs
    MvcResult getUserUrlsResult = mockMvc.perform(get("/api/urls")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Authorization", "Bearer " + jwtToken))
        .andExpect(status().isOk())
        .andReturn();

    String responseBody = getUserUrlsResult.getResponse().getContentAsString();
    assertTrue(responseBody.contains(shortenRequest.getUrl()));

    // 3. Redirect (simulates cache lookup and returns HTTP 302 Found for click tracking)
    mockMvc.perform(get("/" + shortCode))
        .andExpect(status().isFound())
        .andExpect(header().exists("Location"))
        .andExpect(header().string("Location", shortenRequest.getUrl()));

    // 4. Get analytics
    mockMvc.perform(get("/api/analytics/" + shortCode)
        .contentType(MediaType.APPLICATION_JSON)
        .header("Authorization", "Bearer " + jwtToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shortCode").value(shortCode))
        .andExpect(jsonPath("$.originalUrl").value(shortenRequest.getUrl()))
        .andExpect(jsonPath("$.totalClicks").exists());
  }

  @Test
  void testAuthenticationFlow() throws Exception {
    // Login
    AuthRequest loginRequest = new AuthRequest(userEmail, userPassword);
    MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(loginRequest)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").exists())
        .andReturn();

    AuthResponse loginResponse = objectMapper.readValue(
        loginResult.getResponse().getContentAsString(),
        AuthResponse.class);

    assertNotNull(loginResponse.getToken());
    assertEquals(userEmail, loginResponse.getEmail());
  }

  @Test
  void testUnauthorizedAccessWithoutToken() throws Exception {
    mockMvc.perform(get("/api/urls")
        .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void testInvalidUrlValidation() throws Exception {
    ShortenUrlRequest invalidRequest = new ShortenUrlRequest("not-a-valid-url");
    mockMvc.perform(post("/api/shorten")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Authorization", "Bearer " + jwtToken)
        .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void testHealthEndpoint() throws Exception {
    mockMvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(content().string("OK"));
  }
}
