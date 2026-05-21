package com.wordplay.leaderboard.repository;

import com.wordplay.play.entity.PlayRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LeaderboardRepository extends JpaRepository<PlayRecord, Long> {

    @Query("""
        SELECT p FROM PlayRecord p
        WHERE p.gameId = :gameId
          AND p.status = com.wordplay.play.entity.PlayStatus.SOLVED
        ORDER BY p.attemptCount ASC, p.timeSpentSec ASC NULLS LAST
    """)
    List<PlayRecord> findRankings(@Param("gameId") String gameId, Pageable pageable);

    @Query("""
        SELECT COUNT(p) FROM PlayRecord p
        WHERE p.gameId = :gameId
          AND p.status = com.wordplay.play.entity.PlayStatus.SOLVED
    """)
    long countSolvers(@Param("gameId") String gameId);
}
