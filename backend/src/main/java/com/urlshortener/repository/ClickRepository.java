package com.urlshortener.repository;

import com.urlshortener.entity.Click;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository interface for Click entity.
 * Provides custom queries for analytics and click tracking.
 */
@Repository
public interface ClickRepository extends JpaRepository<Click, Long> {

  /**
   * Count total clicks for a specific URL.
   * Used in analytics dashboard for total click count.
   */
  Long countByUrlId(Long urlId);

  /**
   * Find all clicks for a specific URL within a time range.
   * Used for time-series analytics (e.g., clicks over last 7 days).
   */
  List<Click> findByUrlIdAndTimestampBetween(Long urlId, LocalDateTime startTime, LocalDateTime endTime);

  /**
   * Find all clicks for a specific URL.
   * Used for detailed analytics.
   */
  List<Click> findByUrlId(Long urlId);

  /**
   * Count clicks grouped by country for a specific URL.
   * 
   * @return Array of [country, count] pairs
   */
  @Query("""
          SELECT c.country, COUNT(c) FROM Click c
          WHERE c.url.id = :urlId
          GROUP BY c.country
          ORDER BY COUNT(c) DESC
      """)
  List<Object[]> countClicksByCountry(@Param("urlId") Long urlId);

  /**
   * Count clicks grouped by browser.
   */
  @Query("""
          SELECT c.browser, COUNT(c) FROM Click c
          WHERE c.url.id = :urlId
          GROUP BY c.browser
          ORDER BY COUNT(c) DESC
      """)
  List<Object[]> countClicksByBrowser(@Param("urlId") Long urlId);

  /**
   * Count clicks grouped by device type.
   */
  @Query("""
          SELECT c.deviceType, COUNT(c) FROM Click c
          WHERE c.url.id = :urlId
          GROUP BY c.deviceType
          ORDER BY COUNT(c) DESC
      """)
  List<Object[]> countClicksByDeviceType(@Param("urlId") Long urlId);

  /**
   * Count clicks grouped by OS.
   */
  @Query("""
          SELECT c.os, COUNT(c) FROM Click c
          WHERE c.url.id = :urlId
          GROUP BY c.os
          ORDER BY COUNT(c) DESC
      """)
  List<Object[]> countClicksByOS(@Param("urlId") Long urlId);

  /**
   * Count clicks grouped by date for time-series data.
   * Used for the "clicks over time" chart.
   */
  @Query("""
          SELECT CAST(c.timestamp AS date), COUNT(c) FROM Click c
          WHERE c.url.id = :urlId
          GROUP BY CAST(c.timestamp AS date)
          ORDER BY CAST(c.timestamp AS date) ASC
      """)
  List<Object[]> countClicksByDate(@Param("urlId") Long urlId);
}
