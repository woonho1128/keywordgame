package com.wordplay.feedback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    List<Feedback> findTop200ByOrderByIdDesc();

    long countByCreatedAtAfter(Instant since);
}
