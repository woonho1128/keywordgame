package com.wordplay.spyfall;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.spyfall.dto.NewGameRequest;
import com.wordplay.spyfall.dto.SpyfallStateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/spyfall")
@RequiredArgsConstructor
public class SpyfallController {

    private final SpyfallService spyfallService;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    /** 새 판 생성(새로고침). 인원수/스파이 수 지정. */
    @PostMapping("/new")
    public ApiResponse<SpyfallStateResponse> newGame(@Valid @RequestBody NewGameRequest req) {
        return ApiResponse.success(spyfallService.newGame(req.playerCount(), req.spyCount()));
    }

    /** 버튼 클릭: 좌석 배정 or 기존 역할 반환. */
    @PostMapping("/claim")
    public ApiResponse<SpyfallStateResponse> claim(@RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(spyfallService.claim(clientId));
    }

    /** 재접속 시 현재 역할 조회(좌석을 새로 잡지 않음). */
    @GetMapping("/me")
    public ApiResponse<SpyfallStateResponse> me(@RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(spyfallService.state(clientId));
    }

    /** 관리자 코드 확인(초기화 버튼 노출용). */
    @GetMapping("/admin/verify")
    public ApiResponse<Boolean> verifyAdmin(@RequestParam String code) {
        return ApiResponse.success(adminCode.equals(code));
    }

    /** 전체 초기화(관리자 코드 필요). */
    @PostMapping("/reset")
    public ApiResponse<SpyfallStateResponse> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        }
        return ApiResponse.success(spyfallService.reset());
    }

    private void validateClientId(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 64) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "clientId가 올바르지 않습니다");
        }
    }
}
