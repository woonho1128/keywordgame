package com.wordplay.tetris;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.mafia.dto.JoinRequest;
import com.wordplay.tetris.TetrisBattleRankService.WinRow;
import com.wordplay.tetris.dto.BattleSyncRequest;
import com.wordplay.tetris.dto.NewBattleRequest;
import com.wordplay.tetris.dto.TetrisBattleState;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tetris-battle")
@RequiredArgsConstructor
public class TetrisBattleController {

    private final TetrisBattleRoomManager rooms;
    private final TetrisBattleRankService rankService;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() { return ApiResponse.success(rooms.list()); }

    @GetMapping("/ranking")
    public ApiResponse<List<WinRow>> ranking(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(rankService.top(limit));
    }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<TetrisBattleState>> newGame(@RequestParam String clientId,
                                                                      @RequestBody NewBattleRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req);
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<TetrisBattleState> join(@RequestParam String roomCode, @RequestParam String clientId,
                                               @RequestBody JoinRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).join(clientId, req.nick()));
    }

    @PostMapping("/add-bot")
    public ApiResponse<TetrisBattleState> addBot(@RequestParam String roomCode, @RequestParam String clientId,
                                                 @RequestParam(defaultValue = "NORMAL") String level) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).addBot(clientId, level));
    }

    @PostMapping("/start")
    public ApiResponse<TetrisBattleState> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).start(clientId));
    }

    @PostMapping("/sync")
    public ApiResponse<TetrisBattleState> sync(@RequestParam String roomCode, @RequestParam String clientId,
                                               @RequestBody BattleSyncRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).sync(clientId, req.attacks(), req.heights(), req.alive()));
    }

    @GetMapping("/me")
    public ApiResponse<TetrisBattleState> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        TetrisBattleGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(TetrisBattleState.notFound(System.currentTimeMillis()));
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
