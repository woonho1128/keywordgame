package com.wordplay.yacht;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.mafia.dto.JoinRequest;
import com.wordplay.yacht.dto.NewYachtRequest;
import com.wordplay.yacht.dto.YachtState;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/yacht-room")
@RequiredArgsConstructor
public class YachtController {

    private final YachtRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() { return ApiResponse.success(rooms.list()); }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<YachtState>> newGame(@RequestParam String clientId,
                                                               @RequestBody NewYachtRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req);
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<YachtState> join(@RequestParam String roomCode, @RequestParam String clientId,
                                        @RequestBody JoinRequest req) {
        validateClientId(clientId);
        YachtGame g = rooms.require(roomCode);
        g.join(clientId, req.nick());
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/add-bot")
    public ApiResponse<YachtState> addBot(@RequestParam String roomCode, @RequestParam String clientId,
                                          @RequestParam(defaultValue = "NORMAL") String level) {
        validateClientId(clientId);
        YachtGame g = rooms.require(roomCode);
        g.addBot(clientId, level);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/start")
    public ApiResponse<YachtState> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        YachtGame g = rooms.require(roomCode);
        g.start(clientId);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/roll")
    public ApiResponse<YachtState> roll(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        YachtGame g = rooms.require(roomCode);
        g.roll(clientId);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/hold")
    public ApiResponse<YachtState> hold(@RequestParam String roomCode, @RequestParam String clientId,
                                        @RequestParam int index) {
        validateClientId(clientId);
        YachtGame g = rooms.require(roomCode);
        g.toggleHold(clientId, index);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/pick")
    public ApiResponse<YachtState> pick(@RequestParam String roomCode, @RequestParam String clientId,
                                        @RequestParam String cat) {
        validateClientId(clientId);
        YachtGame g = rooms.require(roomCode);
        g.pick(clientId, cat);
        return ApiResponse.success(g.me(clientId));
    }

    @GetMapping("/me")
    public ApiResponse<YachtState> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        YachtGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(YachtState.notFound(System.currentTimeMillis()));
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
