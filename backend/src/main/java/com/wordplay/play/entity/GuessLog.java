package com.wordplay.play.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "TB_GUESS_LOG")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "record_id", nullable = false)
    private Long recordId;

    @Column(name = "guess_word", nullable = false, length = 100)
    private String guessWord;

    @Column(name = "guess_order", nullable = false)
    private Integer guessOrder;

    @Column(name = "similarity")
    private Float similarity;

    @Column(name = "rank_value")
    private Integer rankValue;

    /**
     * WordGuess JSONB: [{"syllable":"사","cho":"H","jung":"H","jong":null}, ...]
     * Hibernate 6의 @JdbcTypeCode(SqlTypes.JSON)로 PostgreSQL JSONB와 매핑.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "letter_result", columnDefinition = "jsonb")
    private String letterResult;

    @Column(name = "is_correct", nullable = false)
    private Boolean isCorrect;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (isCorrect == null) isCorrect = false;
    }
}
