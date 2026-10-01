package com.urlshortener.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Service for geo-location lookup based on IP address.
 * 
 * Uses ip-api.com free tier (1000 requests/month, ~1.2 requests/second).
 * For production at scale, consider upgrading to paid tier or using different provider.
 * 
 * Response example:
 * {
 *   "status": "success",
 *   "country": "US",
 *   "city": "San Francisco",
 *   ...
 * }
 */
@Service
public class GeolocationService {
    
    @Value("${app.geolocation.api-url:https://ip-api.com/json/}")
    private String geolocationApiUrl;
    
    @Value("${app.geolocation.timeout-seconds:5}")
    private int timeoutSeconds;
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    public GeolocationService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }
    
    /**
     * Look up country and city for an IP address.
     * Returns a map with "country" and "city" keys, or empty map on failure.
     * 
     * Called asynchronously so it doesn't block redirect response.
     */
    public Mono<Map<String, String>> getGeoLocation(String ip) {
        if (isLocalIp(ip)) {
            // For local/private IPs, return a mock country & city so localhost testing shows analytics
            Map<String, String> mockGeo = new HashMap<>();
            String[] countries = {"IN", "US", "RU", "DE", "GB", "CA", "FR", "JP"};
            String[] cities = {"New Delhi", "New York", "Moscow", "Berlin", "London", "Toronto", "Paris", "Tokyo"};
            int index = Math.abs(ip == null ? 0 : ip.hashCode()) % countries.length;
            mockGeo.put("country", countries[index]);
            mockGeo.put("city", cities[index]);
            return Mono.just(mockGeo);
        }

        return webClient.get()
            .uri(geolocationApiUrl + ip)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseGeoResponse)
            .onErrorResume(e -> {
                // Log error but don't crash; geolocation is nice-to-have
                return Mono.just(new HashMap<>());
            });
    }

    /**
     * Check if an IP address is a local or loopback address.
     */
    private boolean isLocalIp(String ip) {
        if (ip == null || ip.isEmpty()) return true;
        String cleanIp = ip.trim();
        return cleanIp.equals("127.0.0.1") || 
               cleanIp.equals("0:0:0:0:0:0:0:1") || 
               cleanIp.equalsIgnoreCase("localhost") ||
               cleanIp.startsWith("192.168.") || 
               cleanIp.startsWith("10.") || 
               cleanIp.startsWith("172.16.") || 
               cleanIp.startsWith("172.17.") || 
               cleanIp.startsWith("172.18.") || 
               cleanIp.startsWith("172.19.") || 
               cleanIp.startsWith("172.2") || 
               cleanIp.startsWith("172.30.") || 
               cleanIp.startsWith("172.31.") || 
               cleanIp.startsWith("127.");
    }
    
    /**
     * Parse the IP API response.
     */
    private Map<String, String> parseGeoResponse(String jsonResponse) {
        Map<String, String> result = new HashMap<>();
        
        try {
            JsonNode node = objectMapper.readTree(jsonResponse);
            
            if ("success".equals(node.get("status").asText())) {
                String country = node.get("countryCode").asText();
                String city = node.get("city").asText();
                
                result.put("country", country);
                result.put("city", city);
            }
        } catch (Exception e) {
            // Silently fail; don't crash the analytics logging
        }
        
        return result;
    }
}
