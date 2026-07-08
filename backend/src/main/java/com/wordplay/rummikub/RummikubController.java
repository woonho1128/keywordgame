package com.wordplay.rummikub;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.mafia.dto.JoinRequest;
import com.wordplay.rummikub.dto.NewRummikubRequest;
import com.wordplay.rummikub.dto.PlayRequest;
import com.wordplay.rummikub.dto.RummikubStateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rummikub")
@RequiredArgsConstructor
public class RummikubController {

    private final RummikubRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() {
        return ApiResponse.success(rooms.list());
    }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<RummikubStateResponse>> newGame(@RequestParam String clientId,
                                                                          @Valid @RequestBody NewRummikubRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req.nick());
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<RummikubStateResponse> join(@RequestParam String roomCode, @RequestParam String clientId,
                                                   @Valid @RequestBody JoinRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).join(clientId, req.nick()));
    }

    @PostMapping("/add-ai")
    public ApiResponse<RummikubStateResponse> addAi(@RequestParam String roomCode, @RequestParam String clientId,
                                                    @RequestParam(defaultValue = "NORMAL") String level) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).addAi(clientId, level));
    }

    @PostMapping("/remove-ai")
    public ApiResponse<RummikubStateResponse> removeAi(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).removeAi(clientId));
    }

    @PostMapping("/start")
    public ApiResponse<RummikubStateResponse> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).start(clientId));
    }

    @GetMapping("/me")
    public ApiResponse<RummikubStateResponse> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        RummikubGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(RummikubStateResponse.notStarted(System.currentTimeMillis()));
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/play")
    public ApiResponse<RummikubStateResponse> play(@RequestParam String roomCode, @RequestParam String clientId,
                                                   @RequestBody PlayRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).play(clientId, req.table()));
    }

    @PostMapping("/draw")
    public ApiResponse<RummikubStateResponse> draw(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).draw(clientId));
    }

    @PostMapping("/reset")
    public ApiResponse<RummikubStateResponse> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        rooms.resetAll();
        return ApiResponse.success(RummikubStateResponse.notStarted(System.currentTimeMillis()));
    }

    private void validateClientId(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 64)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "clientId가 올바르지 않습니다");
    }
}
