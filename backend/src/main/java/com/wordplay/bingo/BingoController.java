package com.wordplay.bingo;

import com.wordplay.bingo.dto.BingoStateResponse;
import com.wordplay.bingo.dto.NewBingoRequest;
import com.wordplay.bingo.dto.SetBoardRequest;
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
@RequestMapping("/api/v1/bingo")
@RequiredArgsConstructor
public class BingoController {

    private final BingoRoomManager rooms;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() {
        return ApiResponse.success(rooms.list());
    }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<BingoStateResponse>> newGame(@RequestParam String clientId,
                                                                       @Valid @RequestBody NewBingoRequest req) {
        validateClientId(clientId);
        String code = rooms.create(clientId, req);
        return ApiResponse.success(new CreateRoomResponse<>(code, rooms.require(code).me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<BingoStateResponse> join(@RequestParam String roomCode, @RequestParam String clientId,
                                                @Valid @RequestBody JoinRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).join(clientId, req.nick()));
    }

    @PostMapping("/set-board")
    public ApiResponse<BingoStateResponse> setBoard(@RequestParam String roomCode, @RequestParam String clientId,
                                                    @RequestBody SetBoardRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).setBoard(clientId, req.numbers()));
    }

    @PostMapping("/start")
    public ApiResponse<BingoStateResponse> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).start(clientId));
    }

    /** TURN 모드: 자기 차례에 숫자 지목. */
    @PostMapping("/call")
    public ApiResponse<BingoStateResponse> call(@RequestParam String roomCode, @RequestParam String clientId,
                                                @RequestParam int number) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).callNumber(clientId, number));
    }

    @GetMapping("/me")
    public ApiResponse<BingoStateResponse> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        BingoGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(BingoStateResponse.notStarted(System.currentTimeMillis()));
        return ApiResponse.success(g.me(clientId));
    }

    @PostMapping("/reset")
    public ApiResponse<BingoStateResponse> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        rooms.resetAll();
        return ApiResponse.success(BingoStateResponse.notStarted(System.currentTimeMillis()));
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
