package com.wordplay.avalon;

import com.wordplay.avalon.dto.AvalonStateResponse;
import com.wordplay.avalon.dto.FlagRequest;
import com.wordplay.avalon.dto.NewAvalonRequest;
import com.wordplay.avalon.dto.ProposeRequest;
import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.mafia.dto.JoinRequest;
import com.wordplay.mafia.dto.TargetRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/avalon")
@RequiredArgsConstructor
public class AvalonController {

    private final AvalonRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() {
        return ApiResponse.success(rooms.list());
    }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<AvalonStateResponse>> newGame(@RequestParam String clientId,
                                                                        @Valid @RequestBody NewAvalonRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req);
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<AvalonStateResponse> join(@RequestParam String roomCode, @RequestParam String clientId,
                                                 @Valid @RequestBody JoinRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).join(clientId, req.nick()));
    }

    @PostMapping("/start")
    public ApiResponse<AvalonStateResponse> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).start(clientId));
    }

    @GetMapping("/me")
    public ApiResponse<AvalonStateResponse> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        AvalonService g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(AvalonStateResponse.notStarted(System.currentTimeMillis()));
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/ready")
    public ApiResponse<AvalonStateResponse> ready(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).ready(clientId));
    }

    @PostMapping("/propose")
    public ApiResponse<AvalonStateResponse> propose(@RequestParam String roomCode, @RequestParam String clientId,
                                                    @RequestBody ProposeRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).propose(clientId, req.team()));
    }

    @PostMapping("/vote")
    public ApiResponse<AvalonStateResponse> vote(@RequestParam String roomCode, @RequestParam String clientId,
                                                 @RequestBody FlagRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).vote(clientId, req.value()));
    }

    @PostMapping("/quest")
    public ApiResponse<AvalonStateResponse> quest(@RequestParam String roomCode, @RequestParam String clientId,
                                                  @RequestBody FlagRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).quest(clientId, req.value()));
    }

    @PostMapping("/assassinate")
    public ApiResponse<AvalonStateResponse> assassinate(@RequestParam String roomCode, @RequestParam String clientId,
                                                        @RequestBody TargetRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).assassinate(clientId, req.target()));
    }

    @PostMapping("/reset")
    public ApiResponse<AvalonStateResponse> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        rooms.resetAll();
        return ApiResponse.success(AvalonStateResponse.notStarted(System.currentTimeMillis()));
    }

    private void validateClientId(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 64)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "clientId가 올바르지 않습니다");
    }
}
