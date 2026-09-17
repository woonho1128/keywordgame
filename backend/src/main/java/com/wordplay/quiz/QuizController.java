package com.wordplay.quiz;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.mafia.dto.JoinRequest;
import com.wordplay.quiz.dto.NewQuizRequest;
import com.wordplay.quiz.dto.QuizState;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/quiz")
@RequiredArgsConstructor
public class QuizController {

    private final QuizRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() { return ApiResponse.success(rooms.list()); }

    /** AI 출제 가능 여부. 꺼져 있으면 내장 문제로 돌아간다. */
    @GetMapping("/ai-available")
    public ApiResponse<Boolean> aiAvailable() { return ApiResponse.success(rooms.aiAvailable()); }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<QuizState>> newGame(@RequestParam String clientId,
                                                             @RequestBody NewQuizRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req);
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<QuizState> join(@RequestParam String roomCode, @RequestParam String clientId,
                                       @RequestBody JoinRequest req) {
        validateClientId(clientId);
        QuizGame g = rooms.require(roomCode);
        g.join(clientId, req.nick());
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/start")
    public ApiResponse<QuizState> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        QuizGame g = rooms.require(roomCode);
        g.start(clientId);
        return ApiResponse.success(g.me(clientId));
    }

    /** 답 제출. 객관식은 보기 번호(0~3), 주관식은 쓴 답. */
    @PostMapping("/answer")
    public ApiResponse<QuizState> answer(@RequestParam String roomCode, @RequestParam String clientId,
                                         @RequestParam String value) {
        validateClientId(clientId);
        QuizGame g = rooms.require(roomCode);
        g.answer(clientId, value);
        return ApiResponse.success(g.me(clientId));
    }

    /** 난이도·제한시간별 시간 배틀 순위. */
    @GetMapping("/ranks")
    public ApiResponse<List<com.wordplay.quiz.dto.QuizRankRow>> ranks(
            @RequestParam(defaultValue = "5") int level,
            @RequestParam(defaultValue = "120") int limitSec,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(rooms.ranks(level, limitSec, limit));
    }

    /** 무제한 모드에서 모르는 문제를 넘긴다. */
    @PostMapping("/skip")
    public ApiResponse<QuizState> skip(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        QuizGame g = rooms.require(roomCode);
        g.skip(clientId);
        return ApiResponse.success(g.me(clientId));
    }

    @GetMapping("/me")
    public ApiResponse<QuizState> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        QuizGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(QuizState.notFound(System.currentTimeMillis()));
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/leave")
    public ApiResponse<Boolean> leave(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        rooms.leave(roomCode, clientId);
        return ApiResponse.success(true);
    }

    @PostMapping("/reset")
    public ApiResponse<Boolean> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        rooms.resetAll();
        return ApiResponse.success(true);
    }

    private void validateClientId(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 64)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "clientId가 올바르지 않습니다");
    }
}
