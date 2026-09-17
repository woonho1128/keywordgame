package com.wordplay.saju.dto;

import java.time.Instant;
import java.time.LocalDate;

/** 사주 조회/공유 응답 */
public record SajuReadingResponse(
        String readingId,
        String shareUrl,
        String sajuType,
        String typeLabel,
        String typeEmoji,
        String nickname,
        LocalDate birthDate,
        String birthTimeLabel,
        String gender,
        SajuChart chart,
        SajuResult result,
        Instant createdAt
) {}
