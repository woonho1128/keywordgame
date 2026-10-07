package com.wordplay.game.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "TB_GAME")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Game {

    @Id
    @Column(name = "game_id", length = 12)
    private String gameId;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_type", nullable = false, length = 20)
    private GameType gameType;

    /** 게임 제목 — 구분용. 신규 게임은 필수, 제목 도입 이전 게임은 null일 수 있음. */
    @Column(name = "title", length = 60)
    private String title;

    @Column(name = "answer_word", nullable = false, length = 100)
    private String answerWord;

    @Column(name = "word_length", nullable = false)
    private Integer wordLength;

    @Column(name = "hint_text", length = 500)
    private String hintText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "game_config", columnDefinition = "jsonb")
    private String gameConfig;

    @Column(name = "creator_nick", length = 50)
    private String creatorNick;

    @Column(name = "is_public", nullable = false)
    private Boolean isPublic;

    @Column(name = "play_count", nullable = false)
    private Integer playCount;

    @Column(name = "solved_count", nullable = false)
    private Integer solvedCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 플레이어에게 내려줄 음절 수.
     * WordGuess는 추측 음절 수가 자유라 정답 음절 수 자체가 힌트가 되므로 숨긴다 (자모 수만 공개).
     */
    public Integer publicWordLength() {
        return gameType == GameType.WORDGUESS ? null : wordLength;
    }

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (isPublic == null) isPublic = true;
        if (playCount == null) playCount = 0;
        if (solvedCount == null) solvedCount = 0;
    }
}
