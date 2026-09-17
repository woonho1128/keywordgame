package com.wordplay.saju.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.util.NanoIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Predicate;

/**
 * 사주·궁합 서비스가 같이 쓰는 잔손질 — JSON 변환, 캐시 키 해시, 공유 ID 발급.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SajuSupport {

    private final ObjectMapper objectMapper;

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "결과 저장에 실패했습니다");
        }
    }

    public <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "저장된 결과를 읽지 못했습니다");
        }
    }

    /**
     * AI 응답(JSON 문자열) 파싱 — 실패하면 AI 실패로 처리한다.
     * 실패 이유를 반드시 남긴다. 안 남기면 운영에서 왜 깨졌는지 알 길이 없다.
     */
    public <T> T parseAiJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("AI response parse failed ({}): {} / raw={}",
                    type.getSimpleName(), e.getMessage(), truncate(json, 400));
            throw new BusinessException(ErrorCode.SAJU_AI_FAILED);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return "null";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    public String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "캐시 키 생성에 실패했습니다");
        }
    }

    /** 중복되지 않는 공유 ID 발급 */
    public String generateId(int length, Predicate<String> exists) {
        for (int i = 0; i < 5; i++) {
            String id = NanoIdGenerator.generate(length);
            if (!exists.test(id)) return id;
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to generate unique id");
    }

    public static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
