package com.wordplay.othello;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.mafia.dto.JoinRequest;
import com.wordplay.othello.dto.NewOthelloRequest;
import com.wordplay.othello.dto.OthelloStateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/othello")
@RequiredArgsConstructor
public class OthelloController {

    private final OthelloRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() {
        return ApiResponse.success(rooms.list());
    }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<OthelloStateResponse>> newGame(@RequestParam String clientId,
                                                                         @Valid @RequestBody NewOthelloRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req);
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<OthelloStateResponse> join(@RequestParam String roomCode, @RequestParam String clientId,
                                                  @Valid @RequestBody JoinRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).join(clientId, req.nick()));
    }

    @PostMapping("/add-bot")
    public ApiResponse<OthelloStateResponse> addBot(@RequestParam String roomCode, @RequestParam String clientId,
                                                    @RequestParam(defaultValue = "NORMAL") String level) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).addBot(clientId, level));
    }

    @PostMapping("/start")
    public ApiResponse<OthelloStateResponse> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).start(clientId));
    }

    @PostMapping("/place")
    public ApiResponse<OthelloStateResponse> place(@RequestParam String roomCode, @RequestParam String clientId,
                                                   @RequestParam int cell) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).place(clientId, cell));
    }

    @GetMapping("/me")
    public ApiResponse<OthelloStateResponse> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        OthelloGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(OthelloStateResponse.notStarted(System.currentTimeMillis()));
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/reset")
    public ApiResponse<OthelloStateResponse> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        rooms.resetAll();
        return ApiResponse.success(OthelloStateResponse.notStarted(System.currentTimeMillis()));
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
