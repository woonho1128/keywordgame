package com.wordplay.halligalli;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.halligalli.dto.HalliGalliStateResponse;
import com.wordplay.halligalli.dto.NewHalliGalliRequest;
import com.wordplay.mafia.dto.JoinRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/v1/halligalli")
@RequiredArgsConstructor
public class HalliGalliController {

    private final HalliGalliRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() {
        return ApiResponse.success(rooms.list());
    }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<HalliGalliStateResponse>> newGame(@RequestParam String clientId,
                                                                            @Valid @RequestBody NewHalliGalliRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req.nick());
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<HalliGalliStateResponse> join(@RequestParam String roomCode, @RequestParam String clientId,
                                                     @Valid @RequestBody JoinRequest req) {
        validateClientId(clientId);
        HalliGalliStateResponse res = rooms.require(roomCode).join(clientId, req.nick());
        rooms.broadcast(roomCode);
        return ApiResponse.success(res);
    }

    @PostMapping("/add-ai")
    public ApiResponse<HalliGalliStateResponse> addAi(@RequestParam String roomCode, @RequestParam String clientId,
                                                      @RequestParam(defaultValue = "NORMAL") String level) {
        validateClientId(clientId);
        HalliGalliStateResponse res = rooms.require(roomCode).addAi(clientId, level);
        rooms.broadcast(roomCode);
        return ApiResponse.success(res);
    }

    @PostMapping("/remove-ai")
    public ApiResponse<HalliGalliStateResponse> removeAi(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        HalliGalliStateResponse res = rooms.require(roomCode).removeAi(clientId);
        rooms.broadcast(roomCode);
        return ApiResponse.success(res);
    }

    @PostMapping("/start")
    public ApiResponse<HalliGalliStateResponse> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        HalliGalliStateResponse res = rooms.require(roomCode).start(clientId);
        rooms.broadcast(roomCode);
        return ApiResponse.success(res);
    }

    @PostMapping("/flip")
    public ApiResponse<HalliGalliStateResponse> flip(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        HalliGalliStateResponse res = rooms.require(roomCode).flip(clientId);
        rooms.broadcast(roomCode);
        return ApiResponse.success(res);
    }

    @PostMapping("/ring")
    public ApiResponse<HalliGalliStateResponse> ring(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        HalliGalliStateResponse res = rooms.require(roomCode).ring(clientId);
        rooms.broadcast(roomCode);
        return ApiResponse.success(res);
    }

    @GetMapping("/me")
    public ApiResponse<HalliGalliStateResponse> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        HalliGalliGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(HalliGalliStateResponse.notStarted(System.currentTimeMillis()));
        if (g.tick()) rooms.broadcast(roomCode); // 봇 진행(폴링에 얹어 실시간 동작)
        return ApiResponse.success(g.me(clientId));
    }

    /** 실시간 상태 스트림(SSE). */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String roomCode, @RequestParam String clientId,
                             HttpServletResponse response) {
        validateClientId(clientId);
        response.setHeader("X-Accel-Buffering", "no"); // 프록시 버퍼링 방지
        response.setHeader("Cache-Control", "no-cache");
        return rooms.subscribe(roomCode, clientId);
    }

    @PostMapping("/reset")
    public ApiResponse<HalliGalliStateResponse> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        rooms.resetAll();
        return ApiResponse.success(HalliGalliStateResponse.notStarted(System.currentTimeMillis()));
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
