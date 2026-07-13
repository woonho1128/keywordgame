package com.wordplay.coup;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.coup.dto.CoupActionRequest;
import com.wordplay.coup.dto.CoupStateResponse;
import com.wordplay.coup.dto.NewCoupRequest;
import com.wordplay.mafia.dto.JoinRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/coup")
@RequiredArgsConstructor
public class CoupController {

    private final CoupRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() { return ApiResponse.success(rooms.list()); }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<CoupStateResponse>> newGame(@RequestParam String clientId,
                                                                      @Valid @RequestBody NewCoupRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req);
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<CoupStateResponse> join(@RequestParam String roomCode, @RequestParam String clientId,
                                               @Valid @RequestBody JoinRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).join(clientId, req.nick()));
    }

    @PostMapping("/add-bot")
    public ApiResponse<CoupStateResponse> addBot(@RequestParam String roomCode, @RequestParam String clientId,
                                                 @RequestParam(defaultValue = "NORMAL") String level) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).addBot(clientId, level));
    }

    @PostMapping("/start")
    public ApiResponse<CoupStateResponse> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).start(clientId));
    }

    @PostMapping("/act")
    public ApiResponse<CoupStateResponse> act(@RequestParam String roomCode, @RequestParam String clientId,
                                              @RequestBody CoupActionRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).act(clientId, req.action(), req.target()));
    }

    @PostMapping("/respond")
    public ApiResponse<CoupStateResponse> respond(@RequestParam String roomCode, @RequestParam String clientId,
                                                  @RequestBody CoupActionRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).respond(clientId, req.decision(), req.blockCard()));
    }

    @PostMapping("/lose-card")
    public ApiResponse<CoupStateResponse> loseCard(@RequestParam String roomCode, @RequestParam String clientId,
                                                   @RequestBody CoupActionRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).loseCard(clientId, req.cardIndex() == null ? 0 : req.cardIndex()));
    }

    @PostMapping("/exchange")
    public ApiResponse<CoupStateResponse> exchange(@RequestParam String roomCode, @RequestParam String clientId,
                                                   @RequestBody CoupActionRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).exchange(clientId, req.keep()));
    }

    @GetMapping("/me")
    public ApiResponse<CoupStateResponse> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        CoupGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(CoupStateResponse.notStarted(System.currentTimeMillis()));
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/reset")
    public ApiResponse<CoupStateResponse> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        rooms.resetAll();
        return ApiResponse.success(CoupStateResponse.notStarted(System.currentTimeMillis()));
    }

    @PostMapping("/close-room")
    public ApiResponse<Boolean> closeRoom(@RequestParam String code, @RequestParam String roomCode) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        return ApiResponse.success(rooms.closeRoom(roomCode));
    }

    @PostMapping("/leave")
    public ApiResponse<Boolean> leave(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        rooms.leave(roomCode, clientId);
        return ApiResponse.success(true);
    }

    private void validateClientId(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 64)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "clientId가 올바르지 않습니다");
    }
}
