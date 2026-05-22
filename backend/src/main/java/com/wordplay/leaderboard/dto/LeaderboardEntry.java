package com.wordplay.leaderboard.dto;

import java.time.Instant;

public record LeaderboardEntry(
        Integer rank,               // 성공자 순위 (실패자는 null)
        String playerNick,
        String status,              // "SOLVED" | "GAVE_UP"
        Integer attemptCount,
        Integer timeSpentSec,
        Instant finishedAt,
        // Lie Hint 전용 — 게임을 끝낸 사람이 볼 때만 채워짐(스포일러 방지). 그 외 null.
        Integer selectedLieIndex    // 거짓 힌트로 고른 0-based 번호, 선택 안 했으면 null
) {}
