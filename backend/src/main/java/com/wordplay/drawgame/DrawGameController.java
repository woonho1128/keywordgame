package com.wordplay.drawgame;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.drawgame.dto.DrawGameStateResponse;
import com.wordplay.drawgame.dto.NewDrawGameRequest;
import com.wordplay.drawgame.dto.SubmitRequest;
import com.wordplay.mafia.dto.JoinRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/drawgame")
@RequiredArgsConstructor
public class DrawGameController {

    private final DrawGameRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() {
        return ApiResponse.success(rooms.list());
    }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<DrawGameStateResponse>> newGame(@RequestParam String clientId,
                                                                          @Valid @RequestBody NewDrawGameRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req);
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<DrawGameStateResponse> join(@RequestParam String roomCode, @RequestParam String clientId,
                                                   @Valid @RequestBody JoinRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).join(clientId, req.nick()));
    }

    @PostMapping("/start")
    public ApiResponse<DrawGameStateResponse> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).start(clientId));
    }

    @PostMapping("/submit")
    public ApiResponse<DrawGameStateResponse> submit(@RequestParam String roomCode, @RequestParam String clientId,
                                                     @RequestBody SubmitRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).submit(clientId, req.type(), req.content()));
    }

    @PostMapping("/snapshot")
    public ApiResponse<DrawGameStateResponse> snapshot(@RequestParam String roomCode, @RequestParam String clientId,
                                                       @RequestBody SubmitRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).snapshotImg(clientId, req.content()));
    }

    @PostMapping("/guess")
    public ApiResponse<DrawGameStateResponse> guess(@RequestParam String roomCode, @RequestParam String clientId,
                                                    @RequestBody SubmitRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).guess(clientId, req.content()));
    }

    @GetMapping("/me")
    public ApiResponse<DrawGameStateResponse> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        DrawGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(DrawGameStateResponse.notStarted(System.currentTimeMillis()));
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/reset")
    public ApiResponse<DrawGameStateResponse> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        rooms.resetAll();
        return ApiResponse.success(DrawGameStateResponse.notStarted(System.currentTimeMillis()));
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
