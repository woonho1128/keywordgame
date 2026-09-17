package com.wordplay.saju.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * AI가 돌려준 해석 결과.
 *
 * <p>모델이 스키마를 조금 벗어나도 깨지지 않도록 모르는 필드는 무시하고, 없는 필드는 null로 둔다.
 * strengths·cautions·timeline 은 나중에 추가된 필드라 예전에 저장된 해석에는 없다.
 * 화면에서 null 처리를 해야 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SajuResult(
        String headline,
        String summary,
        List<Highlight> highlights,
        List<Section> sections,
        List<String> strengths,
        List<String> cautions,
        List<Period> timeline,
        List<String> keywords,
        Lucky lucky,
        String advice,
        Integer score
) {

    /**
     * 결과 맨 위에 한눈에 보여주는 요점.
     * 예) 미래인연 — {label:"앞으로 만날 인연", value:"3번", detail:"재성이 셋이라 …"}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Highlight(String label, String value, String detail) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Section(String title, String body) {}

    /** 시기별 흐름 한 구간 (예: "30~39세 무신 대운") */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Period(String period, String body) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lucky(String color, String number, String direction, String item) {}

    /** 필수 필드가 채워졌는지 — AI 응답 검증용 */
    public boolean isUsable() {
        return summary != null && !summary.isBlank()
                && sections != null && !sections.isEmpty();
    }
}
