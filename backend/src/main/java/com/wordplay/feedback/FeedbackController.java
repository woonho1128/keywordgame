package com.wordplay.feedback;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.feedback.dto.FeedbackRequest;
import com.wordplay.feedback.dto.FeedbackView;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService service;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    @PostMapping
    public ApiResponse<Boolean> submit(@RequestBody FeedbackRequest req, HttpServletRequest http) {
        service.submit(req, clientIp(http), http.getHeader("User-Agent"));
        return ApiResponse.success(true);
    }

    /** 관리자 조회(최근 200건). */
    @GetMapping
    public ApiResponse<List<FeedbackView>> list(@RequestParam String code) {
        requireAdmin(code);
        return ApiResponse.success(service.list());
    }

    /**
     * 관리자 진단: 메일을 실제로 한 통 보내보고 결과·설정을 응답으로 돌려준다.
     * 로그를 뒤지지 않아도 실패 사유(키 미설정, 수신자 제한 등)를 바로 확인할 수 있다.
     */
    @PostMapping("/test-mail")
    public ApiResponse<String> testMail(@RequestParam String code) {
        requireAdmin(code);
        return ApiResponse.success(service.testMail());
    }

    private void requireAdmin(String code) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
    }

    private static String clientIp(HttpServletRequest r) {
        String xff = r.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return r.getRemoteAddr();
    }
}
