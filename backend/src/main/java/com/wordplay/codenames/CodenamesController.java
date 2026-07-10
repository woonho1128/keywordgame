package com.wordplay.codenames;

import com.wordplay.codenames.dto.ClueRequest;
import com.wordplay.codenames.dto.CodenamesStateResponse;
import com.wordplay.codenames.dto.IndexRequest;
import com.wordplay.codenames.dto.NewCodenamesRequest;
import com.wordplay.codenames.dto.TeamRequest;
import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.mafia.dto.JoinRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/codenames")
@RequiredArgsConstructor
public class CodenamesController {

    private final CodenamesRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() {
        return ApiResponse.success(rooms.list());
    }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<CodenamesStateResponse>> newGame(@RequestParam String clientId,
                                                                           @Valid @RequestBody NewCodenamesRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req.nick());
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<CodenamesStateResponse> join(@RequestParam String roomCode, @RequestParam String clientId,
                                                    @Valid @RequestBody JoinRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).join(clientId, req.nick()));
    }

    @PostMapping("/team")
    public ApiResponse<CodenamesStateResponse> team(@RequestParam String roomCode, @RequestParam String clientId,
                                                    @RequestBody TeamRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).setTeam(clientId, req.team()));
    }

    @PostMapping("/spymaster")
    public ApiResponse<CodenamesStateResponse> spymaster(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).claimSpymaster(clientId));
    }

    @PostMapping("/random-assign")
    public ApiResponse<CodenamesStateResponse> randomAssign(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).randomAssign(clientId));
    }

    @PostMapping("/start")
    public ApiResponse<CodenamesStateResponse> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).start(clientId));
    }

    @GetMapping("/me")
    public ApiResponse<CodenamesStateResponse> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        CodenamesGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(CodenamesStateResponse.notStarted(System.currentTimeMillis()));
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/clue")
    public ApiResponse<CodenamesStateResponse> clue(@RequestParam String roomCode, @RequestParam String clientId,
                                                    @RequestBody ClueRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).clue(clientId, req.word(), req.number()));
    }

    @PostMapping("/guess")
    public ApiResponse<CodenamesStateResponse> guess(@RequestParam String roomCode, @RequestParam String clientId,
                                                     @RequestBody IndexRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).guess(clientId, req.index()));
    }

    @PostMapping("/pass")
    public ApiResponse<CodenamesStateResponse> pass(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).pass(clientId));
    }

    @PostMapping("/reset")
    public ApiResponse<CodenamesStateResponse> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        rooms.resetAll();
        return ApiResponse.success(CodenamesStateResponse.notStarted(System.currentTimeMillis()));
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
