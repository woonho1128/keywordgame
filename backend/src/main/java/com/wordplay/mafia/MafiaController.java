package com.wordplay.mafia;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.mafia.dto.JoinRequest;
import com.wordplay.mafia.dto.MafiaStateResponse;
import com.wordplay.mafia.dto.NewMafiaRequest;
import com.wordplay.mafia.dto.TargetRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/mafia")
@RequiredArgsConstructor
public class MafiaController {

    private final MafiaRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    /** 방 목록. */
    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() {
        return ApiResponse.success(rooms.list());
    }

    /** 방 생성 + 방장 참가 → 방 코드 반환. */
    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<MafiaStateResponse>> newGame(@RequestParam String clientId,
                                                                       @Valid @RequestBody NewMafiaRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req);
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<MafiaStateResponse> join(@RequestParam String roomCode, @RequestParam String clientId,
                                                @Valid @RequestBody JoinRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).join(clientId, req.nick()));
    }

    @PostMapping("/start")
    public ApiResponse<MafiaStateResponse> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).start(clientId));
    }

    /** 폴링. 방이 사라졌으면 NOT_STARTED로 알려 목록으로 돌아가게 한다. */
    @GetMapping("/me")
    public ApiResponse<MafiaStateResponse> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        MafiaService g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(MafiaStateResponse.notStarted(System.currentTimeMillis()));
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/night-action")
    public ApiResponse<MafiaStateResponse> nightAction(@RequestParam String roomCode, @RequestParam String clientId,
                                                       @RequestBody TargetRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).nightAction(clientId, req.target()));
    }

    @PostMapping("/vote")
    public ApiResponse<MafiaStateResponse> vote(@RequestParam String roomCode, @RequestParam String clientId,
                                                @RequestBody TargetRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).vote(clientId, req.target()));
    }

    @PostMapping("/skip-discuss")
    public ApiResponse<MafiaStateResponse> skipDiscuss(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).skipDiscuss(clientId));
    }

    /** 전체 방 초기화(관리자). */
    @PostMapping("/reset")
    public ApiResponse<MafiaStateResponse> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        rooms.resetAll();
        return ApiResponse.success(MafiaStateResponse.notStarted(System.currentTimeMillis()));
    }

    @PostMapping("/close-room")
    public ApiResponse<Boolean> closeRoom(@RequestParam String code, @RequestParam String roomCode) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        return ApiResponse.success(rooms.closeRoom(roomCode));
    }

    private void validateClientId(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 64)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "clientId가 올바르지 않습니다");
    }
}
