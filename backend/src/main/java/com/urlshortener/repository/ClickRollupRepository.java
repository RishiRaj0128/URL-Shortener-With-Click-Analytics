package com.urlshortener.repository;

import com.urlshortener.entity.ClickRollup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClickRollupRepository extends JpaRepository<ClickRollup, Long> {

    Optional<ClickRollup> findByShortCodeAndWindowStart(String shortCode, LocalDateTime windowStart);

    @Query("SELECT r FROM ClickRollup r WHERE r.shortCode = :shortCode AND r.windowStart >= :since ORDER BY r.windowStart ASC")
    List<ClickRollup> findRecentRollups(@Param("shortCode") String shortCode, @Param("since") LocalDateTime since);

    @Query("SELECT r FROM ClickRollup r WHERE r.isAnomaly = true ORDER BY r.windowStart DESC")
    List<ClickRollup> findAnomalousRollups();

    @Query("SELECT r.shortCode, SUM(r.legitimateClicks) as total FROM ClickRollup r GROUP BY r.shortCode ORDER BY total DESC")
    List<Object[]> findTopUrlsByClicks();
}
