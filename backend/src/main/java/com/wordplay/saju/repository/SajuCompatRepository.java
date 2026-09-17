package com.wordplay.saju.repository;

import com.wordplay.saju.entity.SajuCompat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SajuCompatRepository extends JpaRepository<SajuCompat, String> {

    /** 같은 두 사람 + 같은 종류로 이미 뽑아둔 궁합 (최신 1건) */
    Optional<SajuCompat> findFirstByCacheKeyOrderByCreatedAtDesc(String cacheKey);
}
