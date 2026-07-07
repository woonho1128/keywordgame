package com.wordplay.jobmafia;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.jobmafia.dto.JobMafiaStateResponse;
import com.wordplay.jobmafia.dto.NewJobMafiaRequest;
import com.wordplay.mafia.dto.JoinRequest;
import com.wordplay.mafia.dto.TargetRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/jobmafia")
@RequiredArgsConstructor
public class JobMafiaController {

    private final JobMafiaRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() {
        return ApiResponse.success(rooms.list());
    }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<JobMafiaStateResponse>> newGame(@RequestParam String clientId,
                                                                          @Valid @RequestBody NewJobMafiaRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req);
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<JobMafiaStateResponse> join(@RequestParam String roomCode, @RequestParam String clientId,
                                                   @Valid @RequestBody JoinRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).join(clientId, req.nick()));
    }

    @PostMapping("/start")
    public ApiResponse<JobMafiaStateResponse> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).start(clientId));
    }

    @GetMapping("/me")
    public ApiResponse<JobMafiaStateResponse> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        JobMafiaService g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(JobMafiaStateResponse.notStarted(System.currentTimeMillis()));
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/night-action")
    public ApiResponse<JobMafiaStateResponse> nightAction(@RequestParam String roomCode, @RequestParam String clientId,
                                                          @RequestBody TargetRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).nightAction(clientId, req.target()));
    }

    @PostMapping("/vote")
    public ApiResponse<JobMafiaStateResponse> vote(@RequestParam String roomCode, @RequestParam String clientId,
                                                   @RequestBody TargetRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).vote(clientId, req.target()));
    }

    @PostMapping("/reset")
    public ApiResponse<JobMafiaStateResponse> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        rooms.resetAll();
        return ApiResponse.success(JobMafiaStateResponse.notStarted(System.currentTimeMillis()));
    }

    private void validateClientId(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 64)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "clientId가 올바르지 않습니다");
    }
}
