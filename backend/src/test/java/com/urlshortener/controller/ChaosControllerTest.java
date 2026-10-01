package com.urlshortener.controller;

import com.urlshortener.resilience.ChaosState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Track 3.3: ChaosController Failure Injection Tests")
class ChaosControllerTest {

    private ChaosState chaosState;
    private ChaosController controller;

    @BeforeEach
    void setUp() {
        chaosState = new ChaosState();
        controller = new ChaosController(chaosState);
        chaosState.reset();
    }

    @Test
    @DisplayName("Should inject and report simulated Redis latency")
    void shouldInjectRedisLatency() {
        ResponseEntity<Map<String, Object>> response = controller.injectRedisSlow(600, 10);
        assertEquals(200, response.getStatusCode().value());

        ChaosState.ChaosStatus status = controller.getStatus().getBody();
        assertNotNull(status);
        assertTrue(status.redisSlow());
        assertEquals(600, status.redisLatencyMs());
    }

    @Test
    @DisplayName("Should inject and report simulated Redis outage")
    void shouldInjectRedisOutage() {
        controller.injectRedisFail(10);

        ChaosState.ChaosStatus status = controller.getStatus().getBody();
        assertNotNull(status);
        assertTrue(status.redisDown());
    }

    @Test
    @DisplayName("Should pause and resume consumer processing")
    void shouldToggleConsumerPause() {
        controller.killConsumer();
        assertTrue(controller.getStatus().getBody().consumerPaused());

        controller.restartConsumer();
        assertFalse(controller.getStatus().getBody().consumerPaused());
    }

    @Test
    @DisplayName("Reset should clear all active chaos states")
    void shouldResetAllChaosStates() {
        controller.injectRedisSlow(500, 10);
        controller.injectRedisFail(10);
        controller.killConsumer();

        controller.resetChaos();

        ChaosState.ChaosStatus status = controller.getStatus().getBody();
        assertNotNull(status);
        assertFalse(status.redisSlow());
        assertFalse(status.redisDown());
        assertFalse(status.consumerPaused());
        assertEquals(0, status.redisLatencyMs());
    }
}
