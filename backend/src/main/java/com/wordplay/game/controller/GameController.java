package com.wordplay.game.controller;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.game.dto.CreateGameRequest;
import com.wordplay.game.dto.CreateGameResponse;
import com.wordplay.game.dto.GameResponse;
import com.wordplay.game.dto.RecentGameItem;
import com.wordplay.game.entity.GameType;
import com.wordplay.game.service.GameService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/games")
@RequiredArgsConstructor
public class GameController {

    private final GameService gameService;

    @PostMapping
    public ApiResponse<CreateGameResponse> create(@Valid @RequestBody CreateGameRequest req) {
        return ApiResponse.success(gameService.createGame(req));
    }

    @GetMapping("/{gameId}")
    public ApiResponse<GameResponse> get(@PathVariable String gameId) {
        return ApiResponse.success(gameService.getGame(gameId));
    }

    @GetMapping("/recent")
    public ApiResponse<Page<RecentGameItem>> recent(
            @RequestParam(required = false) GameType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(gameService.getRecentGames(type, page, size));
    }
}
