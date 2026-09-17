package com.wordplay.spicy;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.mafia.dto.JoinRequest;
import com.wordplay.spicy.dto.NewSpicyRequest;
import com.wordplay.spicy.dto.SpicyState;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/spicy")
@RequiredArgsConstructor
public class SpicyController {

    private final SpicyRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() { return ApiResponse.success(rooms.list()); }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<SpicyState>> newGame(@RequestParam String clientId,
                                                               @RequestBody NewSpicyRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req);
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<SpicyState> join(@RequestParam String roomCode, @RequestParam String clientId,
                                        @RequestBody JoinRequest req) {
        validateClientId(clientId);
        SpicyGame g = rooms.require(roomCode);
        g.join(clientId, req.nick());
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/add-bot")
    public ApiResponse<SpicyState> addBot(@RequestParam String roomCode, @RequestParam String clientId,
                                          @RequestParam(defaultValue = "NORMAL") String level) {
        validateClientId(clientId);
        SpicyGame g = rooms.require(roomCode);
        g.addBot(clientId, level);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/start")
    public ApiResponse<SpicyState> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        SpicyGame g = rooms.require(roomCode);
        g.start(clientId);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/play")
    public ApiResponse<SpicyState> play(@RequestParam String roomCode, @RequestParam String clientId,
                                        @RequestParam int cardId, @RequestParam int spice, @RequestParam int number) {
        validateClientId(clientId);
        SpicyGame g = rooms.require(roomCode);
        g.play(clientId, cardId, spice, number);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/pass")
    public ApiResponse<SpicyState> pass(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        SpicyGame g = rooms.require(roomCode);
        g.pass(clientId);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/challenge")
    public ApiResponse<SpicyState> challenge(@RequestParam String roomCode, @RequestParam String clientId,
                                             @RequestParam String kind) {
        validateClientId(clientId);
        SpicyGame g = rooms.require(roomCode);
        g.challenge(clientId, kind);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/pass-challenge")
    public ApiResponse<SpicyState> passChallenge(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        SpicyGame g = rooms.require(roomCode);
        g.passChallenge(clientId);
        return ApiResponse.success(g.me(clientId));
    }

    @GetMapping("/me")
    public ApiResponse<SpicyState> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        SpicyGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(SpicyState.notFound(System.currentTimeMillis()));
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
