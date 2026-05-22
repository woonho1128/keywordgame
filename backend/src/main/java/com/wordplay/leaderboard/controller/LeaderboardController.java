package com.wordplay.leaderboard.controller;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.util.SessionManager;
import com.wordplay.leaderboard.dto.LeaderboardResponse;
import com.wordplay.leaderboard.service.LeaderboardService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/games/{gameId}/leaderboard")
@RequiredArgsConstructor
public class LeaderboardController {

    private final LeaderboardService leaderboardService;
    private final SessionManager sessionManager;

    @GetMapping
    public ApiResponse<LeaderboardResponse> get(
            @PathVariable String gameId,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request
    ) {
        // 세션이 있으면 전달 — Lie Hint 선택 상세 공개 자격 판정에 쓰인다
        String sessionKey = sessionManager.extract(request);
        return ApiResponse.success(leaderboardService.getLeaderboard(gameId, limit, sessionKey));
    }
}
