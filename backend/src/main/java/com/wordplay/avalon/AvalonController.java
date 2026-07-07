package com.wordplay.avalon;

import com.wordplay.avalon.dto.AvalonStateResponse;
import com.wordplay.avalon.dto.FlagRequest;
import com.wordplay.avalon.dto.NewAvalonRequest;
import com.wordplay.avalon.dto.ProposeRequest;
import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.mafia.dto.JoinRequest;
import com.wordplay.mafia.dto.TargetRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/avalon")
@RequiredArgsConstructor
public class AvalonController {

    private final AvalonService service;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @PostMapping("/new")
    public ApiResponse<AvalonStateResponse> newGame(@RequestParam String clientId,
                                                    @Valid @RequestBody NewAvalonRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(service.newGame(clientId, req));
    }

    @PostMapping("/join")
    public ApiResponse<AvalonStateResponse> join(@RequestParam String clientId,
                                                 @Valid @RequestBody JoinRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(service.join(clientId, req.nick()));
    }

    @PostMapping("/start")
    public ApiResponse<AvalonStateResponse> start(@RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(service.start(clientId));
    }

    @GetMapping("/me")
    public ApiResponse<AvalonStateResponse> me(@RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(service.me(clientId));
    }

    @PostMapping("/ready")
    public ApiResponse<AvalonStateResponse> ready(@RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(service.ready(clientId));
    }

    @PostMapping("/propose")
    public ApiResponse<AvalonStateResponse> propose(@RequestParam String clientId,
                                                    @RequestBody ProposeRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(service.propose(clientId, req.team()));
    }

    @PostMapping("/vote")
    public ApiResponse<AvalonStateResponse> vote(@RequestParam String clientId,
                                                 @RequestBody FlagRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(service.vote(clientId, req.value()));
    }

    @PostMapping("/quest")
    public ApiResponse<AvalonStateResponse> quest(@RequestParam String clientId,
                                                  @RequestBody FlagRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(service.quest(clientId, req.value()));
    }

    @PostMapping("/assassinate")
    public ApiResponse<AvalonStateResponse> assassinate(@RequestParam String clientId,
                                                        @RequestBody TargetRequest req) {
        validateClientId(clientId);
        return ApiResponse.success(service.assassinate(clientId, req.target()));
    }

    @PostMapping("/reset")
    public ApiResponse<AvalonStateResponse> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        return ApiResponse.success(service.resetGame());
    }

    private void validateClientId(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 64)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "clientId가 올바르지 않습니다");
    }
}
