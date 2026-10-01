package com.urlshortener.dto;

import java.util.Map;
import java.util.List;

/**
 * DTO for analytics responses containing click statistics.
 */
public class AnalyticsResponse {
    private String shortCode;
    private String originalUrl;
    private Long totalClicks;
    private Map<String, Long> clicksByCountry;      // Country code -> click count
    private Map<String, Long> clicksByBrowser;      // Browser name -> click count
    private Map<String, Long> clicksByDeviceType;   // Device type -> click count
    private Map<String, Long> clicksByOS;           // OS name -> click count
    private List<DailyClickData> clicksOverTime;    // Time series data for charts
    
    public AnalyticsResponse() {}
    
    public AnalyticsResponse(String shortCode, String originalUrl, Long totalClicks) {
        this.shortCode = shortCode;
        this.originalUrl = originalUrl;
        this.totalClicks = totalClicks;
    }
    
    public String getShortCode() {
        return shortCode;
    }
    
    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }
    
    public String getOriginalUrl() {
        return originalUrl;
    }
    
    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }
    
    public Long getTotalClicks() {
        return totalClicks;
    }
    
    public void setTotalClicks(Long totalClicks) {
        this.totalClicks = totalClicks;
    }
    
    public Map<String, Long> getClicksByCountry() {
        return clicksByCountry;
    }
    
    public void setClicksByCountry(Map<String, Long> clicksByCountry) {
        this.clicksByCountry = clicksByCountry;
    }
    
    public Map<String, Long> getClicksByBrowser() {
        return clicksByBrowser;
    }
    
    public void setClicksByBrowser(Map<String, Long> clicksByBrowser) {
        this.clicksByBrowser = clicksByBrowser;
    }
    
    public Map<String, Long> getClicksByDeviceType() {
        return clicksByDeviceType;
    }
    
    public void setClicksByDeviceType(Map<String, Long> clicksByDeviceType) {
        this.clicksByDeviceType = clicksByDeviceType;
    }
    
    public Map<String, Long> getClicksByOS() {
        return clicksByOS;
    }
    
    public void setClicksByOS(Map<String, Long> clicksByOS) {
        this.clicksByOS = clicksByOS;
    }
    
    public List<DailyClickData> getClicksOverTime() {
        return clicksOverTime;
    }
    
    public void setClicksOverTime(List<DailyClickData> clicksOverTime) {
        this.clicksOverTime = clicksOverTime;
    }
    
    /**
     * Inner DTO for daily click aggregations.
     */
    public static class DailyClickData {
        private String date;  // YYYY-MM-DD format
        private Long clicks;
        
        public DailyClickData() {}
        
        public DailyClickData(String date, Long clicks) {
            this.date = date;
            this.clicks = clicks;
        }
        
        public String getDate() {
            return date;
        }
        
        public void setDate(String date) {
            this.date = date;
        }
        
        public Long getClicks() {
            return clicks;
        }
        
        public void setClicks(Long clicks) {
            this.clicks = clicks;
        }
    }
}
