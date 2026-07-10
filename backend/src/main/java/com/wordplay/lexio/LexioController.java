package com.wordplay.lexio;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.lexio.dto.LexioPlayRequest;
import com.wordplay.lexio.dto.LexioStateResponse;
import com.wordplay.lexio.dto.NewLexioRequest;
import com.wordplay.mafia.dto.JoinRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/lexio")
@RequiredArgsConstructor
public class LexioController {

    private final LexioRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() {
        return ApiResponse.success(rooms.list());
    }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<LexioStateResponse>> newGame(@RequestParam String clientId,
                                                                       @Valid @RequestBody NewLexioRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req);
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<LexioStateResponse> join(@RequestParam String roomCode, @RequestParam String clientId,
                                                @Valid @RequestBody JoinRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).join(clientId, req.nick()));
    }

    @PostMapping("/add-bot")
    public ApiResponse<LexioStateResponse> addBot(@RequestParam String roomCode, @RequestParam String clientId,
                                                  @RequestParam(defaultValue = "NORMAL") String level) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).addBot(clientId, level));
    }

    @PostMapping("/start")
    public ApiResponse<LexioStateResponse> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).start(clientId));
    }

    @PostMapping("/play")
    public ApiResponse<LexioStateResponse> play(@RequestParam String roomCode, @RequestParam String clientId,
                                                @RequestBody LexioPlayRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).play(clientId, req.tiles()));
    }

    @PostMapping("/pass")
    public ApiResponse<LexioStateResponse> pass(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).pass(clientId));
    }

    @PostMapping("/next-round")
    public ApiResponse<LexioStateResponse> nextRound(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).nextRound(clientId));
    }

    @GetMapping("/me")
    public ApiResponse<LexioStateResponse> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        LexioGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(LexioStateResponse.notStarted(System.currentTimeMillis()));
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/reset")
    public ApiResponse<LexioStateResponse> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        rooms.resetAll();
        return ApiResponse.success(LexioStateResponse.notStarted(System.currentTimeMillis()));
    }

    @PostMapping("/close-room")
    public ApiResponse<Boolean> closeRoom(@RequestParam String code, @RequestParam String roomCode) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        return ApiResponse.success(rooms.closeRoom(roomCode));
    }

    @PostMapping("/leave")
    public ApiResponse<Boolean> leave(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        rooms.leave(roomCode, clientId);
        return ApiResponse.success(true);
    }

    private void validateClientId(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 64)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "clientId가 올바르지 않습니다");
    }
}
