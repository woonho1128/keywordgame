package com.wordplay.feedback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    List<Feedback> findTop200ByOrderByIdDesc();

    long countByCreatedAtAfter(Instant since);

    /** 실제로 메일이 나간 건수(무료 한도 관리용). 재시작해도 유지되도록 DB에서 센다. */
    long countByMailSentTrueAndCreatedAtAfter(Instant since);
}
