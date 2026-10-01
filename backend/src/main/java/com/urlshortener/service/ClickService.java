package com.urlshortener.service;

import com.urlshortener.dto.AnalyticsResponse;
import com.urlshortener.entity.Click;
import com.urlshortener.entity.Url;
import com.urlshortener.exception.UrlShortenerException;
import com.urlshortener.repository.ClickRepository;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.util.IpHashUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua_parser.Client;
import ua_parser.Parser;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for click tracking and analytics.
 * 
 * Responsibilities:
 * - Log clicks asynchronously (non-blocking)
 * - Extract device/browser/OS info from User-Agent header
 * - Fetch geolocation from IP
 * - Provide analytics queries
 * 
 * Async Strategy:
 * - logClick() is @Async to avoid blocking redirect response
 * - Geolocation lookup happens in background
 * - Database inserts happen after response is sent
 */
@Service
@Transactional
public class ClickService {
    
    private final ClickRepository clickRepository;
    private final UrlRepository urlRepository;
    private final GeolocationService geolocationService;
    private final Parser uaParser;
    
    @Autowired
    public ClickService(
            ClickRepository clickRepository,
            UrlRepository urlRepository,
            GeolocationService geolocationService) throws Exception {
        this.clickRepository = clickRepository;
        this.urlRepository = urlRepository;
        this.geolocationService = geolocationService;
        // ua-parser library for parsing User-Agent strings
        this.uaParser = new Parser();
    }
    
    /**
     * Log a click for a shortened URL.
     * This method is asynchronous and non-blocking.
     * 
     * @param urlId The ID of the shortened URL
     * @param request HTTP request containing User-Agent and IP
     */
    @Async("taskExecutor")
    public void logClick(Long urlId, String userAgent, String referrer, String clientIp) {
        try {
            Url url = urlRepository.findById(urlId)
                .orElse(null);
            
            if (url == null) {
                return;  // URL was deleted; silently ignore
            }
            
            Click click = new Click();
            click.setUrl(url);
            click.setTimestamp(LocalDateTime.now());
            click.setReferrer(referrer);
            click.setIpHash(IpHashUtil.hashIp(clientIp));
            
            // Parse User-Agent
            if (userAgent != null && !userAgent.isEmpty()) {
                Client client = uaParser.parse(userAgent);
                
                click.setBrowser(standardizeBrowser(client.userAgent.family));
                click.setOS(standardizeOS(client.os.family));
                click.setDeviceType(getDeviceType(userAgent));
            } else {
                click.setBrowser("Other");
                click.setOS("Other");
                click.setDeviceType("Desktop");
            }
            
            // Fetch geolocation (async within async method)
            Map<String, String> geo = geolocationService.getGeoLocation(clientIp)
                .block();  // Block here to wait for result before saving
            
            if (geo != null) {
                click.setCountry(geo.get("country"));
                click.setCity(geo.get("city"));
            }
            
            clickRepository.save(click);
        } catch (Exception e) {
            // Log error but don't crash; click logging is best-effort
            System.err.println("Failed to log click: " + e.getMessage());
        }
    }
    
    /**
     * Get total click count for a URL.
     */
    public long getClickCount(Long urlId) {
        Long count = clickRepository.countByUrlId(urlId);
        return count != null ? count : 0L;
    }
    
    /**
     * Get analytics for a shortened URL.
     * Aggregates clicks by country, browser, device type, OS, and time.
     */
    public AnalyticsResponse getAnalytics(String shortCode, String userEmail) {
        Url url = urlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new UrlShortenerException(
                "Short URL not found",
                HttpStatus.NOT_FOUND
            ));
        
        // Verify ownership
        if (!url.getUser().getEmail().equals(userEmail)) {
            throw new UrlShortenerException(
                "Unauthorized to view analytics",
                HttpStatus.FORBIDDEN
            );
        }
        
        Long totalClicks = clickRepository.countByUrlId(url.getId());
        if (totalClicks == null) totalClicks = 0L;
        
        AnalyticsResponse response = new AnalyticsResponse(
            shortCode,
            url.getOriginalUrl(),
            totalClicks
        );
        
        // Aggregate clicks by country
        Map<String, Long> clicksByCountry = aggregateClicks(
            clickRepository.countClicksByCountry(url.getId())
        );
        response.setClicksByCountry(clicksByCountry);
        
        // Aggregate clicks by browser
        Map<String, Long> clicksByBrowser = aggregateClicks(
            clickRepository.countClicksByBrowser(url.getId())
        );
        response.setClicksByBrowser(clicksByBrowser);
        
        // Aggregate clicks by device type
        Map<String, Long> clicksByDeviceType = aggregateClicks(
            clickRepository.countClicksByDeviceType(url.getId())
        );
        response.setClicksByDeviceType(clicksByDeviceType);
        
        // Aggregate clicks by OS
        Map<String, Long> clicksByOS = aggregateClicks(
            clickRepository.countClicksByOS(url.getId())
        );
        response.setClicksByOS(clicksByOS);
        
        // Aggregate clicks over time
        List<AnalyticsResponse.DailyClickData> clicksOverTime = aggregateClicksOverTime(
            clickRepository.countClicksByDate(url.getId())
        );
        response.setClicksOverTime(clicksOverTime);
        
        return response;
    }
    
    /**
     * Convert query results to Map<String, Long>.
     * Query results are Object[] arrays: [dimension, count]
     */
    private Map<String, Long> aggregateClicks(List<Object[]> queryResults) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : queryResults) {
            String key = row[0] != null ? row[0].toString() : "Unknown";
            Long count = ((Number) row[1]).longValue();
            result.put(key, count);
        }
        return result;
    }
    
    /**
     * Aggregate clicks over time for time-series chart.
     * Generates a continuous last 7 days of click data, filling missing dates with 0.
     */
    private List<AnalyticsResponse.DailyClickData> aggregateClicksOverTime(List<Object[]> queryResults) {
        Map<String, Long> dateToCount = new HashMap<>();
        for (Object[] row : queryResults) {
            String date = row[0].toString();
            Long count = ((Number) row[1]).longValue();
            dateToCount.put(date, count);
        }
        
        List<AnalyticsResponse.DailyClickData> result = new ArrayList<>();
        java.time.LocalDate today = java.time.LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        
        for (int i = 6; i >= 0; i--) {
            String dateStr = today.minusDays(i).format(formatter);
            Long count = dateToCount.getOrDefault(dateStr, 0L);
            result.add(new AnalyticsResponse.DailyClickData(dateStr, count));
        }
        
        return result;
    }
    
    /**
     * Extract client IP from request, accounting for proxies.
     */
    public String getClientIp(HttpServletRequest request) {
        String[] headers = {
            "X-Forwarded-For",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_CLIENT_IP",
            "HTTP_X_FORWARDED_FOR"
        };
        
        for (String header : headers) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                return ip.split(",")[0];  // Use first IP if multiple
            }
        }
        
        return request.getRemoteAddr();
    }
    
    /**
     * Determine device type from User-Agent.
     */
    private String getDeviceType(String userAgent) {
        if (userAgent == null) return "Desktop";
        
        String ua = userAgent.toLowerCase();
        
        // Bots and crawlers
        if (ua.contains("bot") || ua.contains("crawler") || ua.contains("spider") || 
            ua.contains("googlebot") || ua.contains("bingbot") || ua.contains("yandexbot") || 
            ua.contains("slurp") || ua.contains("duckduckbot") || ua.contains("baiduspider")) {
            return "Bot/Crawler";
        }
        // Game consoles
        if (ua.contains("playstation") || ua.contains("playstation 3") || ua.contains("playstation 4") || 
            ua.contains("playstation 5") || ua.contains("playstation vita") || ua.contains("psp") ||
            ua.contains("xbox") || ua.contains("nintendo") || ua.contains("wii") || 
            ua.contains("nintendo switch") || ua.contains("nintendo 3ds")) {
            return "Game Console";
        }
        // Smart TVs and media players
        if (ua.contains("smarttv") || ua.contains("smart-tv") || ua.contains("googletv") || 
            ua.contains("appletv") || ua.contains("tizen") || ua.contains("webos") || 
            ua.contains("roku") || ua.contains("firetv") || ua.contains("fire stick") || 
            ua.contains("chromecast") || ua.contains("hbbtv") || ua.contains("netcast")) {
            return "Smart TV";
        }
        // Tablets
        if (ua.contains("tablet") || ua.contains("ipad") || ua.contains("playbook") || 
            (ua.contains("android") && !ua.contains("mobile"))) {
            return "Tablet";
        }
        // Mobile Phones
        if (ua.contains("mobile") || ua.contains("android") || ua.contains("iphone") || 
            ua.contains("ipod") || ua.contains("opera mini") || ua.contains("blackberry") || 
            ua.contains("windows phone") || ua.contains("iemobile")) {
            return "Mobile";
        }
        
        return "Desktop";
    }

    /**
     * Standardize OS names for readable analytics display.
     */
    private String standardizeOS(String osFamily) {
        if (osFamily == null || osFamily.equalsIgnoreCase("Other")) return "Other";
        String os = osFamily.toLowerCase();
        if (os.contains("windows")) return "Windows";
        if (os.contains("mac") || os.contains("os x")) return "macOS";
        if (os.contains("ios") || os.contains("iphone") || os.contains("ipad")) return "iOS";
        if (os.contains("android")) return "Android";
        if (os.contains("linux") || os.contains("ubuntu") || os.contains("debian") || 
            os.contains("fedora") || os.contains("redhat") || os.contains("centos")) return "Linux";
        return osFamily;
    }

    /**
     * Standardize browser names for readable analytics display.
     */
    private String standardizeBrowser(String browserFamily) {
        if (browserFamily == null || browserFamily.equalsIgnoreCase("Other")) return "Other";
        String browser = browserFamily.toLowerCase();
        if (browser.contains("chrome")) return "Chrome";
        if (browser.contains("safari")) return "Safari";
        if (browser.contains("firefox")) return "Firefox";
        if (browser.contains("edge") || browser.contains("edg")) return "Edge";
        if (browser.contains("opera")) return "Opera";
        if (browser.contains("ie") || browser.contains("internet explorer")) return "Internet Explorer";
        return browserFamily;
    }
}
