package com.wordplay.saju.service;

import com.wordplay.saju.domain.CompatType;
import com.wordplay.saju.domain.Compatibility;
import com.wordplay.saju.domain.FourPillars;
import com.wordplay.saju.domain.Gender;
import com.wordplay.saju.domain.SajuType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class SajuPromptBuilderTest {

    private final SajuCalculator calculator = new SajuCalculator(-30);
    private final CompatibilityAnalyzer analyzer = new CompatibilityAnalyzer();
    private final SajuPromptBuilder builder = new SajuPromptBuilder();

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);

    private FourPillars pillars(LocalDate date, LocalTime time, Gender gender) {
        return calculator.calculate(date, time, gender, TODAY);
    }

    @Test
    void 프롬프트에_계산된_사주가_사실로_들어간다() {
        FourPillars p = pillars(LocalDate.of(1990, 5, 15), LocalTime.of(10, 30), Gender.MALE);
        String prompt = builder.userPrompt(SajuType.LOVE, "우노", Gender.MALE, p);

        assertThat(prompt)
                .contains("경오(庚午)")                 // 연주
                .contains(p.day().display())            // 일주
                .contains("일간(본인)")
                .contains("말띠")
                .contains("[대운]")
                .contains("2026년")                     // 세운
                .contains("연애사주")
                .doesNotContain("null");
    }

    @Test
    void 해석_근거가_되는_수치가_모두_들어간다() {
        FourPillars p = pillars(LocalDate.of(1997, 11, 28), LocalTime.of(0, 30), Gender.MALE);
        String prompt = builder.userPrompt(SajuType.TOTAL, "한운호", Gender.MALE, p);

        assertThat(prompt)
                .contains("십성 분포")
                .contains("비겁")
                .contains("재성")
                .contains("지장간")
                .contains("일간의 힘")
                .contains(p.bodyStrength())
                .contains("대운수");
    }

    @Test
    void 섹션_주제는_사주_종류마다_여섯개() {
        FourPillars p = pillars(LocalDate.of(1990, 5, 15), null, Gender.FEMALE);
        for (SajuType type : SajuType.values()) {
            assertThat(type.sectionHints()).hasSize(6);
            assertThat(builder.userPrompt(type, null, Gender.FEMALE, p))
                    .contains(type.sectionHints().get(0))
                    .contains(type.sectionHints().get(5));
        }
    }

    @Test
    void 시간을_모르면_시주를_빼라고_지시한다() {
        FourPillars p = pillars(LocalDate.of(1990, 5, 15), null, Gender.FEMALE);
        String prompt = builder.userPrompt(SajuType.TOTAL, null, Gender.FEMALE, p);

        assertThat(prompt)
                .contains("시주를 뺀 여섯 글자")
                .contains("고객님")
                .doesNotContain("· 시주:")
                .doesNotContain("null");
    }

    @Test
    void 시스템_프롬프트는_계산_금지와_JSON_스키마를_못박는다() {
        assertThat(builder.systemPrompt())
                .contains("다시 계산하거나")
                .contains("\"headline\"")
                .contains("\"sections\"")
                .contains("\"strengths\"")
                .contains("\"cautions\"")
                .contains("\"timeline\"")
                .contains("\"score\"")
                .contains("5~8문장");
    }

    @Test
    void 궁합_프롬프트에_두사람과_계산된_관계가_들어간다() {
        FourPillars a = pillars(LocalDate.of(1997, 11, 28), LocalTime.of(0, 30), Gender.MALE);
        FourPillars b = pillars(LocalDate.of(1998, 3, 14), LocalTime.of(14, 0), Gender.FEMALE);
        Compatibility compat = analyzer.analyze(a, b);

        String prompt = builder.compatUserPrompt(
                CompatType.LOVE, "한운호", Gender.MALE, a, "지은", Gender.FEMALE, b, compat);

        assertThat(prompt)
                .contains("[A] 한운호")
                .contains("[B] 지은")
                .contains("[계산된 관계]")
                .contains("궁합 점수: " + compat.score() + "점")
                .contains("A의 일간이 보는 B")
                .contains("연인궁합")
                .doesNotContain("null");
    }

    @Test
    void 궁합_시스템_프롬프트는_양방향과_점수일치를_요구한다() {
        assertThat(builder.compatSystemPrompt())
                .contains("한 사람만 칭찬하거나")
                .contains("주어진 점수와 어긋나는")
                .contains("\"aToB\"")
                .contains("\"bToA\"");
    }
}
