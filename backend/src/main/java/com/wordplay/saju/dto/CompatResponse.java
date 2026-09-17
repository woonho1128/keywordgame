package com.wordplay.saju.dto;

import java.time.Instant;

/** 궁합 조회/공유 응답 */
public record CompatResponse(
        String compatId,
        String shareUrl,
        String compatType,
        String typeLabel,
        String typeEmoji,
        CompatAnalysis analysis,
        CompatResult result,
        Instant createdAt
) {}
