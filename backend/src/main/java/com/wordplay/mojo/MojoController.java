package com.wordplay.mojo;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.mafia.dto.JoinRequest;
import com.wordplay.mojo.dto.MojoState;
import com.wordplay.mojo.dto.NewMojoRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/mojo")
@RequiredArgsConstructor
public class MojoController {

    private final MojoRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() { return ApiResponse.success(rooms.list()); }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<MojoState>> newGame(@RequestParam String clientId,
                                                              @RequestBody NewMojoRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req);
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<MojoState> join(@RequestParam String roomCode, @RequestParam String clientId,
                                       @RequestBody JoinRequest req) {
        validateClientId(clientId);
        MojoGame g = rooms.require(roomCode);
        g.join(clientId, req.nick());
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/add-bot")
    public ApiResponse<MojoState> addBot(@RequestParam String roomCode, @RequestParam String clientId,
                                         @RequestParam(defaultValue = "NORMAL") String level) {
        validateClientId(clientId);
        MojoGame g = rooms.require(roomCode);
        g.addBot(clientId, level);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/start")
    public ApiResponse<MojoState> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        MojoGame g = rooms.require(roomCode);
        g.start(clientId);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/play")
    public ApiResponse<MojoState> play(@RequestParam String roomCode, @RequestParam String clientId,
                                       @RequestParam int value, @RequestParam(defaultValue = "0") int pile) {
        validateClientId(clientId);
        MojoGame g = rooms.require(roomCode);
        g.play(clientId, value, pile);
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/reveal")
    public ApiResponse<MojoState> reveal(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        MojoGame g = rooms.require(roomCode);
        g.reveal(clientId);
        return ApiResponse.success(g.me(clientId));
    }

    @GetMapping("/me")
    public ApiResponse<MojoState> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        MojoGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(MojoState.notFound(System.currentTimeMillis()));
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
