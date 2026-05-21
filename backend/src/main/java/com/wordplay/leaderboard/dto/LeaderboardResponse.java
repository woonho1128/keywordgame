package com.wordplay.leaderboard.dto;

import java.util.List;

public record LeaderboardResponse(
        Integer totalPlayers,
        List<LeaderboardEntry> rankings
) {}
