package com.wordplay.mafia;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.mafia.dto.ChatRequest;
import com.wordplay.mafia.dto.JoinRequest;
import com.wordplay.mafia.dto.MafiaStateResponse;
import com.wordplay.mafia.dto.NewMafiaRequest;
import com.wordplay.mafia.dto.TargetRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/mafia")
@RequiredArgsConstructor
public class MafiaController {

    private final MafiaService mafiaService;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    /** 방 생성 + 방장 참가. */
    @PostMapping("/new")
    public ApiResponse<MafiaStateResponse> newGame(@RequestParam String clientId,
                                                   @Valid @RequestBody NewMafiaRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(mafiaService.newGame(clientId, req));
    }

    /** 대기방 참가. */
    @PostMapping("/join")
    public ApiResponse<MafiaStateResponse> join(@RequestParam String clientId,
                                                @Valid @RequestBody JoinRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(mafiaService.join(clientId, req.nick()));
    }

    /** 방장이 게임 시작. */
    @PostMapping("/start")
    public ApiResponse<MafiaStateResponse> start(@RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(mafiaService.start(clientId));
    }

    /** 폴링. */
    @GetMapping("/me")
    public ApiResponse<MafiaStateResponse> me(@RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(mafiaService.me(clientId));
    }

    /** 밤 행동(마피아/경찰/의사). */
    @PostMapping("/night-action")
    public ApiResponse<MafiaStateResponse> nightAction(@RequestParam String clientId,
                                                       @RequestBody TargetRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(mafiaService.nightAction(clientId, req.target()));
    }

    /** 마피아 밤 채팅. */
    @PostMapping("/chat")
    public ApiResponse<MafiaStateResponse> chat(@RequestParam String clientId,
                                                @Valid @RequestBody ChatRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(mafiaService.chat(clientId, req.text()));
    }

    /** 낮 투표. */
    @PostMapping("/vote")
    public ApiResponse<MafiaStateResponse> vote(@RequestParam String clientId,
                                                @RequestBody TargetRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(mafiaService.vote(clientId, req.target()));
    }

    /** 전체 초기화(관리자 코드 필요). */
    @PostMapping("/reset")
    public ApiResponse<MafiaStateResponse> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        }
        return ApiResponse.success(mafiaService.resetGame());
    }

    private void validateClientId(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 64) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "clientId가 올바르지 않습니다");
        }
    }
}
