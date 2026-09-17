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
        List<Encounter> encounters,
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

    /**
     * 인연이 들어오는(들어왔던) 해 — 미래인연 전용. 다른 종류는 비워둔다.
     *
     * @param year  연도와 간지 (예: "2027년 정미년")
     * @param past  지난 기회인지
     * @param where 어디서 — 사주 용어가 아니라 실제 장소/상황 (예: "일터·업무 모임")
     * @param story 뭐 하다 만나는지 (또는 그때 어떤 기회였는지)
     * @param basis 그렇게 본 사주 근거 (짧게)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Encounter(String year, boolean past, String where, String story, String basis) {}

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
