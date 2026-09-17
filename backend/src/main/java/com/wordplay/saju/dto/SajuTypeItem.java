package com.wordplay.saju.dto;

import com.wordplay.saju.domain.SajuType;

/** 사주 종류 목록 응답 항목 */
public record SajuTypeItem(String code, String label, String emoji, String description) {

    public static SajuTypeItem from(SajuType type) {
        return new SajuTypeItem(type.name(), type.label(), type.emoji(), type.description());
    }
}
