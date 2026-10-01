package com.urlshortener.repository;

import com.urlshortener.entity.ClickEventDedup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClickEventDedupRepository extends JpaRepository<ClickEventDedup, String> {
}
