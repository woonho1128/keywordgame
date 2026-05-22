package com.wordplay.play.repository;

import com.wordplay.play.entity.GuessLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GuessLogRepository extends JpaRepository<GuessLog, Long> {

    List<GuessLog> findByRecordIdOrderByGuessOrderAsc(Long recordId);

    Optional<GuessLog> findFirstByRecordIdAndIsCorrectTrueOrderByGuessOrderAsc(Long recordId);

    List<GuessLog> findByRecordIdInAndIsCorrectTrue(Collection<Long> recordIds);
}
