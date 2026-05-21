package com.wordplay.play.repository;

import com.wordplay.play.entity.PlayRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlayRecordRepository extends JpaRepository<PlayRecord, Long> {

    Optional<PlayRecord> findByGameIdAndSessionKey(String gameId, String sessionKey);
}
