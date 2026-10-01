package com.urlshortener.repository;

import com.urlshortener.entity.ClickAnomalyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClickAnomalyLogRepository extends JpaRepository<ClickAnomalyLog, Long> {

    List<ClickAnomalyLog> findTop50ByOrderByDetectedAtDesc();

    @Query("SELECT a FROM ClickAnomalyLog a WHERE a.shortCode = :shortCode ORDER BY a.detectedAt DESC")
    List<ClickAnomalyLog> findByShortCode(@Param("shortCode") String shortCode);
}
