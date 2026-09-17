package com.wordplay.saju.controller;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.util.SessionManager;
import com.wordplay.saju.dto.SajuReadingResponse;
import com.wordplay.saju.dto.SajuRequest;
import com.wordplay.saju.dto.SajuTypeItem;
import com.wordplay.saju.service.SajuService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/saju")
@RequiredArgsConstructor
public class SajuController {

    private final SajuService sajuService;
    private final SessionManager sessionManager;

    /** 사주 종류 목록 + 사용 가능 여부 */
    @GetMapping("/types")
    public ApiResponse<Map<String, Object>> types() {
        List<SajuTypeItem> types = sajuService.listTypes();
        return ApiResponse.success(Map.of(
                "available", sajuService.isAvailable(),
                "types", types
        ));
    }

    /** 사주 보기 — AI 해석 생성 */
    @PostMapping
    public ApiResponse<SajuReadingResponse> create(
            @Valid @RequestBody SajuRequest req,
            HttpServletRequest httpReq,
            HttpServletResponse httpRes
    ) {
        // 호출 횟수 제한의 기준이 될 세션 키 (없으면 발급)
        String sessionKey = sessionManager.getOrCreate(httpReq, httpRes);
        return ApiResponse.success(sajuService.createReading(req, sessionKey));
    }

    /** 공유 URL로 다시 보기 */
    @GetMapping("/{readingId}")
    public ApiResponse<SajuReadingResponse> get(@PathVariable String readingId) {
        return ApiResponse.success(sajuService.getReading(readingId));
    }
}
