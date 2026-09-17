package com.wordplay.saju.controller;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.util.SessionManager;
import com.wordplay.saju.dto.CompatRequest;
import com.wordplay.saju.dto.CompatResponse;
import com.wordplay.saju.dto.CompatTypeItem;
import com.wordplay.saju.service.SajuCompatService;
import com.wordplay.saju.service.SajuService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/saju/compat")
@RequiredArgsConstructor
public class SajuCompatController {

    private final SajuCompatService compatService;
    private final SajuService sajuService;
    private final SessionManager sessionManager;

    /** 궁합 종류 목록 + 사용 가능 여부 */
    @GetMapping("/types")
    public ApiResponse<Map<String, Object>> types() {
        List<CompatTypeItem> types = compatService.listTypes();
        return ApiResponse.success(Map.of(
                "available", sajuService.isAvailable(),
                "types", types
        ));
    }

    /** 궁합 보기 — AI 해석 생성 */
    @PostMapping
    public ApiResponse<CompatResponse> create(
            @Valid @RequestBody CompatRequest req,
            HttpServletRequest httpReq,
            HttpServletResponse httpRes
    ) {
        String sessionKey = sessionManager.getOrCreate(httpReq, httpRes);
        return ApiResponse.success(compatService.createCompat(req, sessionKey));
    }

    /** 공유 URL로 다시 보기 */
    @GetMapping("/{compatId}")
    public ApiResponse<CompatResponse> get(@PathVariable String compatId) {
        return ApiResponse.success(compatService.getCompat(compatId));
    }
}
