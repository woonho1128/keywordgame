package com.wordplay.saju.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** AI가 돌려준 궁합 해석 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompatResult(
        String headline,
        String summary,
        List<SajuResult.Section> sections,
        List<String> strengths,
        List<String> cautions,
        String aToB,
        String bToA,
        List<String> keywords,
        String advice
) {

    public boolean isUsable() {
        return summary != null && !summary.isBlank()
                && sections != null && !sections.isEmpty();
    }
}
