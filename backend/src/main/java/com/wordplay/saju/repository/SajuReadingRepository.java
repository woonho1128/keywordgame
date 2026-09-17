package com.wordplay.saju.repository;

import com.wordplay.saju.entity.SajuReading;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SajuReadingRepository extends JpaRepository<SajuReading, String> {

    /** 같은 입력으로 이미 뽑아둔 해석 (최신 1건) */
    Optional<SajuReading> findFirstByCacheKeyOrderByCreatedAtDesc(String cacheKey);
}
