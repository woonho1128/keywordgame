package com.wordplay.yut;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.mafia.dto.JoinRequest;
import com.wordplay.yut.dto.NewYutRequest;
import com.wordplay.yut.dto.YutState;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/yut")
@RequiredArgsConstructor
public class YutController {

    private final YutRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() { return ApiResponse.success(rooms.list()); }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<YutState>> newGame(@RequestParam String clientId,
                                                             @RequestBody NewYutRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req);
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<YutState> join(@RequestParam String roomCode, @RequestParam String clientId,
                                      @RequestBody JoinRequest req) {
        validateClientId(clientId);
        YutGame g = rooms.require(roomCode);
        g.join(clientId, req.nick());
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/add-bot")
    public ApiResponse<YutState> addBot(@RequestParam String roomCode, @RequestParam String clientId,
                                        @RequestParam(defaultValue = "NORMAL") String level) {
        validateClientId(clientId);
        YutGame g = rooms.require(roomCode);
        g.addBot(clientId, level);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/start")
    public ApiResponse<YutState> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        YutGame g = rooms.require(roomCode);
        g.start(clientId);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/throw")
    public ApiResponse<YutState> throwYut(@RequestParam String roomCode, @RequestParam String clientId,
                                          @RequestParam(defaultValue = "60") int power) {
        validateClientId(clientId);
        YutGame g = rooms.require(roomCode);
        g.throwYut(clientId, power);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/move")
    public ApiResponse<YutState> move(@RequestParam String roomCode, @RequestParam String clientId,
                                      @RequestParam int value, @RequestParam int tokenIndex, @RequestParam String dest) {
        validateClientId(clientId);
        YutGame g = rooms.require(roomCode);
        g.move(clientId, value, tokenIndex, dest);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/ability")
    public ApiResponse<YutState> ability(@RequestParam String roomCode, @RequestParam String clientId,
                                         @RequestParam(defaultValue = "-1") int tokenIndex,
                                         @RequestParam(defaultValue = "") String cell,
                                         @RequestParam(defaultValue = "-1") int oppSeat,
                                         @RequestParam(defaultValue = "-1") int oppToken,
                                         @RequestParam(defaultValue = "") String choice) {
        validateClientId(clientId);
        YutGame g = rooms.require(roomCode);
        g.useAbility(clientId, tokenIndex, cell, oppSeat, oppToken, choice);
        return ApiResponse.success(g.me(clientId));
    }

    @GetMapping("/me")
    public ApiResponse<YutState> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        YutGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(YutState.notFound(System.currentTimeMillis()));
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
