package com.wordplay.sixnimmt;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.mafia.dto.JoinRequest;
import com.wordplay.sixnimmt.dto.NewSixNimmtRequest;
import com.wordplay.sixnimmt.dto.SixNimmtState;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sixnimmt")
@RequiredArgsConstructor
public class SixNimmtController {

    private final SixNimmtRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() { return ApiResponse.success(rooms.list()); }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<SixNimmtState>> newGame(@RequestParam String clientId,
                                                                  @RequestBody NewSixNimmtRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req);
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<SixNimmtState> join(@RequestParam String roomCode, @RequestParam String clientId,
                                           @RequestBody JoinRequest req) {
        validateClientId(clientId);
        SixNimmtGame g = rooms.require(roomCode);
        g.join(clientId, req.nick());
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/add-bot")
    public ApiResponse<SixNimmtState> addBot(@RequestParam String roomCode, @RequestParam String clientId,
                                             @RequestParam(defaultValue = "NORMAL") String level) {
        validateClientId(clientId);
        SixNimmtGame g = rooms.require(roomCode);
        g.addBot(clientId, level);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/start")
    public ApiResponse<SixNimmtState> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        SixNimmtGame g = rooms.require(roomCode);
        g.start(clientId);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/play")
    public ApiResponse<SixNimmtState> play(@RequestParam String roomCode, @RequestParam String clientId,
                                           @RequestParam int card) {
        validateClientId(clientId);
        SixNimmtGame g = rooms.require(roomCode);
        g.play(clientId, card);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/take-row")
    public ApiResponse<SixNimmtState> takeRow(@RequestParam String roomCode, @RequestParam String clientId,
                                              @RequestParam int row) {
        validateClientId(clientId);
        SixNimmtGame g = rooms.require(roomCode);
        g.takeRow(clientId, row);
        return ApiResponse.success(g.me(clientId));
    }

    @GetMapping("/me")
    public ApiResponse<SixNimmtState> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        SixNimmtGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(SixNimmtState.notFound(System.currentTimeMillis()));
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
