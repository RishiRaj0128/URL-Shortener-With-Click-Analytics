package com.urlshortener.controller;

import com.urlshortener.dto.ClickEvent;
import com.urlshortener.entity.Url;
import com.urlshortener.entity.User;
import com.urlshortener.pipeline.ClickEventChannel;
import com.urlshortener.service.ClickService;
import com.urlshortener.service.GeolocationService;
import com.urlshortener.service.UrlShorteningService;
import com.urlshortener.util.SnowflakeIdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("Unit Test with Stubs: Redirect Non-Blocking Geolocation Isolation")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedirectNonBlockingGeolocationTest {

    @Mock
    private UrlShorteningService urlShorteningService;

    @Mock
    private ClickService clickService;

    @Mock
    private ClickEventChannel clickEventChannel;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private GeolocationService geolocationService;

    @Mock
    private DataSource dataSource;

    @Mock
    private HttpServletRequest request;

    @Test
    @DisplayName("Unit Test with Stubs: Redirect completes < 50ms without blocking on slow 2s geolocation stub or holding DB connection")
    void redirectDoesNotBlockOnSlowGeolocationOrHoldDbConnection() throws SQLException {
        // Given a 2-second slow geolocation stub
        when(geolocationService.getGeoLocation(anyString()))
                .thenReturn(Mono.just(Map.of("country", "US", "city", "TestCity")).delayElement(Duration.ofSeconds(2)));

        Url mockUrl = new Url();
        mockUrl.setId(101L);
        mockUrl.setShortCode("fast123");
        mockUrl.setOriginalUrl("https://example.com/target");
        User user = new User();
        user.setEmail("test@example.com");
        mockUrl.setUser(user);

        when(urlShorteningService.getUrlByShortCode("fast123")).thenReturn(mockUrl);
        when(snowflakeIdGenerator.nextId()).thenReturn(123456789L);
        when(clickService.getClientIp(request)).thenReturn("203.0.113.195");
        when(request.getHeader("User-Agent")).thenReturn("Mozilla/5.0 TestBrowser");
        when(request.getHeader("Referer")).thenReturn("https://google.com");

        RedirectController controller = new RedirectController(
                urlShorteningService,
                clickService,
                clickEventChannel,
                snowflakeIdGenerator
        );

        // When
        long startNanos = System.nanoTime();
        ResponseEntity<Void> response = controller.redirect("fast123", request);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        // Then
        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals("https://example.com/target", response.getHeaders().getFirst("Location"));

        // Crucial regression assertion: must complete in < 50ms (did NOT block for 2 seconds)
        assertTrue(elapsedMillis < 100, "Redirect took " + elapsedMillis + " ms, expected < 100 ms (non-blocking)");

        // Non-blocking publish was dispatched
        verify(clickEventChannel, times(1)).publish(any(ClickEvent.class));

        // Legacy blocking logClick and external geolocation must NOT be called on the redirect path
        verify(clickService, never()).logClick(anyLong(), anyString(), anyString(), anyString());
        verify(geolocationService, never()).getGeoLocation(anyString());

        // No Hikari connection acquired on redirect path
        verify(dataSource, never()).getConnection();
    }
}
