package com.wordplay.play.controller;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.util.SessionManager;
import com.wordplay.play.dto.GiveUpResponse;
import com.wordplay.play.dto.GuessRequest;
import com.wordplay.play.dto.GuessResponse;
import com.wordplay.play.dto.PlayStateResponse;
import com.wordplay.play.dto.StartPlayRequest;
import com.wordplay.play.dto.StartPlayResponse;
import com.wordplay.play.service.GuessService;
import com.wordplay.play.service.PlayService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/games/{gameId}")
@RequiredArgsConstructor
public class PlayController {

    private final PlayService playService;
    private final GuessService guessService;
    private final SessionManager sessionManager;

    @PostMapping("/start")
    public ApiResponse<StartPlayResponse> start(
            @PathVariable String gameId,
            @Valid @RequestBody StartPlayRequest req,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String sessionKey = sessionManager.getOrCreate(request, response);
        return ApiResponse.success(playService.start(gameId, req, sessionKey));
    }

    @GetMapping("/state")
    public ApiResponse<PlayStateResponse> state(
            @PathVariable String gameId,
            HttpServletRequest request
    ) {
        String sessionKey = sessionManager.extract(request);
        if (sessionKey == null) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        return ApiResponse.success(playService.getState(gameId, sessionKey));
    }

    @PostMapping("/guess")
    public ApiResponse<GuessResponse> guess(
            @PathVariable String gameId,
            @Valid @RequestBody GuessRequest req,
            HttpServletRequest request
    ) {
        String sessionKey = sessionManager.extract(request);
        if (sessionKey == null) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        return ApiResponse.success(guessService.guess(gameId, req, sessionKey));
    }

    @PostMapping("/giveup")
    public ApiResponse<GiveUpResponse> giveUp(
            @PathVariable String gameId,
            HttpServletRequest request
    ) {
        String sessionKey = sessionManager.extract(request);
        if (sessionKey == null) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        return ApiResponse.success(playService.giveUp(gameId, sessionKey));
    }
}
