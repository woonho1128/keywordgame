package com.wordplay.play.repository;

import com.wordplay.play.entity.GuessLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GuessLogRepository extends JpaRepository<GuessLog, Long> {

    List<GuessLog> findByRecordIdOrderByGuessOrderAsc(Long recordId);
}
