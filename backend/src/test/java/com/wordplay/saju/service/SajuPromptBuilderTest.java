package com.wordplay.saju.service;

import com.wordplay.saju.domain.FourPillars;
import com.wordplay.saju.domain.Gender;
import com.wordplay.saju.domain.SajuType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class SajuPromptBuilderTest {

    private final SajuCalculator calculator = new SajuCalculator(-30);
    private final SajuPromptBuilder builder = new SajuPromptBuilder();

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);

    @Test
    void 프롬프트에_계산된_사주가_사실로_들어간다() {
        FourPillars p = calculator.calculate(
                LocalDate.of(1990, 5, 15), LocalTime.of(10, 30), Gender.MALE, TODAY);
        String prompt = builder.userPrompt(SajuType.LOVE, "우노", Gender.MALE, p);

        assertThat(prompt)
                .contains("경오(庚午)")                       // 연주
                .contains(p.day().display())                  // 일주
                .contains("일간")
                .contains("띠")
                .contains("[오행 분포]")
                .contains("[대운]")
                .contains("2026년")                           // 세운
                .contains("연애사주")
                .doesNotContain("null");
    }

    @Test
    void 시간을_모르면_시주를_빼라고_지시한다() {
        FourPillars p = calculator.calculate(
                LocalDate.of(1990, 5, 15), null, Gender.FEMALE, TODAY);
        String prompt = builder.userPrompt(SajuType.TOTAL, null, Gender.FEMALE, p);

        assertThat(prompt)
                .contains("시주를 뺀 여섯 글자")
                .contains("고객님")          // 호칭 미입력 시 기본값
                .doesNotContain("- 시주:")
                .doesNotContain("null");
    }

    @Test
    void 시스템_프롬프트는_계산_금지와_JSON_스키마를_못박는다() {
        assertThat(builder.systemPrompt())
                .contains("다시 계산하거나")
                .contains("\"headline\"")
                .contains("\"sections\"")
                .contains("\"score\"");
    }
}
