package com.wordplay.leaderboard.controller;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.leaderboard.dto.LeaderboardResponse;
import com.wordplay.leaderboard.service.LeaderboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/games/{gameId}/leaderboard")
@RequiredArgsConstructor
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    @GetMapping
    public ApiResponse<LeaderboardResponse> get(
            @PathVariable String gameId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ApiResponse.success(leaderboardService.getLeaderboard(gameId, limit));
    }
}
