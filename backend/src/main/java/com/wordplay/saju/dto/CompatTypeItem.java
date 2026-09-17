package com.wordplay.saju.dto;

import com.wordplay.saju.domain.CompatType;

/** 궁합 종류 목록 응답 항목 */
public record CompatTypeItem(String code, String label, String emoji, String description) {

    public static CompatTypeItem from(CompatType type) {
        return new CompatTypeItem(type.name(), type.label(), type.emoji(), type.description());
    }
}
