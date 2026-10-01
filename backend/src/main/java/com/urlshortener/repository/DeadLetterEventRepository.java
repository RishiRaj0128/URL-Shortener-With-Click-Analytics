package com.urlshortener.repository;

import com.urlshortener.entity.DeadLetterEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, Long> {
    List<DeadLetterEvent> findTop50ByOrderByCreatedAtDesc();
    List<DeadLetterEvent> findByReplayedFalseOrderByCreatedAtAsc();
    long countByReplayedFalse();
}
