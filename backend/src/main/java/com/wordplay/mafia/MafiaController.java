package com.wordplay.mafia;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.mafia.ai.MafiaBotRuntime;
import com.wordplay.mafia.dto.ChatRequest;
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
    private final MafiaBotRuntime botRuntime;
    private final MafiaBotCodes botCodes;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    /** AI 봇 추가(관리자) 코드. 전체 초기화 코드와 다르다. */
    @Value("${app.mafia.bot-admin-code}")
    private String botAdminCode;

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

    /** 토론 채팅 전송. */
    @PostMapping("/chat")
    public ApiResponse<MafiaStateResponse> chat(@RequestParam String roomCode, @RequestParam String clientId,
                                                @Valid @RequestBody ChatRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).sendChat(clientId, req.text()));
    }

    /** AI 봇 사용 가능 여부(OpenAI 키 설정 여부). */
    @GetMapping("/ai-available")
    public ApiResponse<Boolean> aiAvailable() {
        return ApiResponse.success(botRuntime.available());
    }

    /** 관리자: 봇 추가용 1회성 코드 발급. 마스터 봇 관리자 코드 필요. */
    @PostMapping("/issue-bot-code")
    public ApiResponse<String> issueBotCode(@RequestParam String code) {
        if (!botAdminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "봇 관리자 코드가 올바르지 않습니다");
        return ApiResponse.success(botCodes.issue());
    }

    /** AI 봇 추가(대기방, 최대 3명). 마스터 봇 관리자 코드 또는 1회성 코드 필요. */
    @PostMapping("/add-bots")
    public ApiResponse<MafiaStateResponse> addBots(@RequestParam String roomCode, @RequestParam String clientId,
                                                   @RequestParam String code, @RequestParam(defaultValue = "1") int count) {
        validateClientId(clientId);
        boolean master = botAdminCode.equals(code);
        if (!master && !botCodes.isValid(code))
            throw new BusinessException(ErrorCode.INVALID_INPUT, "봇 관리자 코드 또는 1회성 코드가 올바르지 않습니다");
        MafiaService g = rooms.require(roomCode);
        g.addBots(count);                 // 실패(대기방 아님/정원 초과) 시 여기서 예외 → 코드 미소비
        if (!master) botCodes.consume(code); // 성공 시에만 1회성 코드 소비
        return ApiResponse.success(g.me(clientId));
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
