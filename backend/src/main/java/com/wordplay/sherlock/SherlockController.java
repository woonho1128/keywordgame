package com.wordplay.sherlock;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.mafia.dto.JoinRequest;
import com.wordplay.sherlock.dto.NewSherlockRequest;
import com.wordplay.sherlock.dto.SherlockState;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sherlock")
@RequiredArgsConstructor
public class SherlockController {

    private final SherlockRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() { return ApiResponse.success(rooms.list()); }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<SherlockState>> newGame(@RequestParam String clientId,
                                                                  @RequestBody NewSherlockRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req);
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<SherlockState> join(@RequestParam String roomCode, @RequestParam String clientId,
                                           @RequestBody JoinRequest req) {
        validateClientId(clientId);
        SherlockGame g = rooms.require(roomCode);
        g.join(clientId, req.nick());
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/add-bot")
    public ApiResponse<SherlockState> addBot(@RequestParam String roomCode, @RequestParam String clientId,
                                             @RequestParam(defaultValue = "NORMAL") String level) {
        validateClientId(clientId);
        SherlockGame g = rooms.require(roomCode);
        g.addBot(clientId, level);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/start")
    public ApiResponse<SherlockState> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        SherlockGame g = rooms.require(roomCode);
        g.start(clientId);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/ask-all")
    public ApiResponse<SherlockState> askAll(@RequestParam String roomCode, @RequestParam String clientId,
                                             @RequestParam int item) {
        validateClientId(clientId);
        SherlockGame g = rooms.require(roomCode);
        g.askAll(clientId, item);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/ask-one")
    public ApiResponse<SherlockState> askOne(@RequestParam String roomCode, @RequestParam String clientId,
                                             @RequestParam int target, @RequestParam int item) {
        validateClientId(clientId);
        SherlockGame g = rooms.require(roomCode);
        g.askOne(clientId, target, item);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/accuse")
    public ApiResponse<SherlockState> accuse(@RequestParam String roomCode, @RequestParam String clientId,
                                             @RequestParam int charId) {
        validateClientId(clientId);
        SherlockGame g = rooms.require(roomCode);
        g.accuse(clientId, charId);
        return ApiResponse.success(g.me(clientId));
    }

    @GetMapping("/me")
    public ApiResponse<SherlockState> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        SherlockGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(SherlockState.notFound(System.currentTimeMillis()));
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
