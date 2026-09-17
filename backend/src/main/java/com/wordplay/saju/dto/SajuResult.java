package com.wordplay.saju.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * AI가 돌려준 해석 결과. 모델이 스키마를 조금 벗어나도 깨지지 않도록
 * 모르는 필드는 무시하고, 없는 필드는 null로 둔다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SajuResult(
        String headline,
        String summary,
        List<Section> sections,
        List<String> keywords,
        Lucky lucky,
        String advice,
        Integer score
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Section(String title, String body) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lucky(String color, String number, String direction, String item) {}

    /** 필수 필드가 채워졌는지 — AI 응답 검증용 */
    public boolean isUsable() {
        return summary != null && !summary.isBlank()
                && sections != null && !sections.isEmpty();
    }
}
