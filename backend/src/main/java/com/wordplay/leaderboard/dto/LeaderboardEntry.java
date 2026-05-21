package com.wordplay.leaderboard.dto;

import java.time.Instant;

public record LeaderboardEntry(
        Integer rank,
        String playerNick,
        Integer attemptCount,
        Integer timeSpentSec,
        Instant solvedAt
) {}
