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
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        return ApiResponse.success(service.list());
    }

    private static String clientIp(HttpServletRequest r) {
        String xff = r.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return r.getRemoteAddr();
    }
}
