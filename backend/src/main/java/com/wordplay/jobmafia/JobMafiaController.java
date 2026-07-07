package com.wordplay.jobmafia;

import com.wordplay.common.dto.ApiResponse;
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

@RestController
@RequestMapping("/api/v1/jobmafia")
@RequiredArgsConstructor
public class JobMafiaController {

    private final JobMafiaService service;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @PostMapping("/new")
    public ApiResponse<JobMafiaStateResponse> newGame(@RequestParam String clientId,
                                                      @Valid @RequestBody NewJobMafiaRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(service.newGame(clientId, req));
    }

    @PostMapping("/join")
    public ApiResponse<JobMafiaStateResponse> join(@RequestParam String clientId,
                                                   @Valid @RequestBody JoinRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(service.join(clientId, req.nick()));
    }

    @PostMapping("/start")
    public ApiResponse<JobMafiaStateResponse> start(@RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(service.start(clientId));
    }

    @GetMapping("/me")
    public ApiResponse<JobMafiaStateResponse> me(@RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(service.me(clientId));
    }

    @PostMapping("/night-action")
    public ApiResponse<JobMafiaStateResponse> nightAction(@RequestParam String clientId,
                                                          @RequestBody TargetRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(service.nightAction(clientId, req.target()));
    }

    @PostMapping("/vote")
    public ApiResponse<JobMafiaStateResponse> vote(@RequestParam String clientId,
                                                   @RequestBody TargetRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(service.vote(clientId, req.target()));
    }

    @PostMapping("/reset")
    public ApiResponse<JobMafiaStateResponse> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        }
        return ApiResponse.success(service.resetGame());
    }

    private void validateClientId(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 64) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "clientId가 올바르지 않습니다");
        }
    }
}
