package com.wordplay.score;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.score.dto.ScoreRow;
import com.wordplay.score.dto.SubmitScoreRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/scores")
@RequiredArgsConstructor
public class ScoreController {

    private final ScoreService scores;

    /** 상위 랭킹 조회. */
    @GetMapping("/{game}")
    public ApiResponse<List<ScoreRow>> top(@PathVariable String game,
                                           @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(scores.top(game, limit));
    }

    /** 점수 제출 → 내 순위 + 상위 목록. */
    @PostMapping("/{game}")
    public ApiResponse<Map<String, Object>> submit(@PathVariable String game,
                                                   @RequestBody SubmitScoreRequest req) {
        int myRank = scores.submit(game, req.nick(), req.score() == null ? 0 : req.score());
        return ApiResponse.success(Map.of("myRank", myRank, "top", scores.top(game, 10)));
    }
}
