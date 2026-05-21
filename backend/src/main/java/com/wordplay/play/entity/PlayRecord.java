package com.wordplay.play.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "TB_PLAY_RECORD",
        uniqueConstraints = @UniqueConstraint(columnNames = {"game_id", "session_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlayRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "record_id")
    private Long recordId;

    @Column(name = "game_id", nullable = false, length = 12)
    private String gameId;

    @Column(name = "player_nick", nullable = false, length = 50)
    private String playerNick;

    @Column(name = "session_key", nullable = false, length = 64)
    private String sessionKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PlayStatus status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "time_spent_sec")
    private Integer timeSpentSec;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @PrePersist
    public void prePersist() {
        if (startedAt == null) startedAt = Instant.now();
        if (status == null) status = PlayStatus.IN_PROGRESS;
        if (attemptCount == null) attemptCount = 0;
    }
}
