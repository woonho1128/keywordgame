package com.wordplay.play.repository;

import com.wordplay.play.entity.PlayRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PlayRecordRepository extends JpaRepository<PlayRecord, Long> {

    Optional<PlayRecord> findByGameIdAndSessionKey(String gameId, String sessionKey);

    /**
     * 추측/포기처럼 기록을 갱신하는 요청용 조회 — SELECT ... FOR UPDATE.
     * 같은 세션의 요청(더블클릭, 연타)을 직렬화해 시도 횟수가 꼬이지 않게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PlayRecord p WHERE p.gameId = :gameId AND p.sessionKey = :sessionKey")
    Optional<PlayRecord> findForUpdate(@Param("gameId") String gameId, @Param("sessionKey") String sessionKey);
}
