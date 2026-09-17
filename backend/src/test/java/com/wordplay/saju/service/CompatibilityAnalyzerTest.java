package com.wordplay.saju.service;

import com.wordplay.saju.domain.CompatSignal;
import com.wordplay.saju.domain.Compatibility;
import com.wordplay.saju.domain.EarthlyBranch;
import com.wordplay.saju.domain.FourPillars;
import com.wordplay.saju.domain.Gender;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CompatibilityAnalyzerTest {

    private final SajuCalculator calculator = new SajuCalculator(-30);
    private final CompatibilityAnalyzer analyzer = new CompatibilityAnalyzer();

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);

    /** 일주는 60일마다 반복되므로 원하는 일지는 60일 안에 반드시 나온다 */
    private FourPillars withDayBranch(EarthlyBranch target, int startYear) {
        LocalDate base = LocalDate.of(startYear, 3, 1);
        for (int i = 0; i < 60; i++) {
            FourPillars p = calculator.calculate(base.plusDays(i), null, Gender.MALE, TODAY);
            if (p.day().branch() == target) return p;
        }
        throw new IllegalStateException("일지 " + target + " 를 못 찾음");
    }

    private boolean hasSignal(Compatibility compat, String position, String relation) {
        return compat.signals().stream()
                .anyMatch(s -> s.position().equals(position) && s.relation().equals(relation));
    }

    @Test
    void 일지_육합이면_좋은_신호로_잡힌다() {
        // 묘-술 육합
        FourPillars a = withDayBranch(EarthlyBranch.MYO, 1990);
        FourPillars b = withDayBranch(EarthlyBranch.SUL, 1992);

        Compatibility compat = analyzer.analyze(a, b);

        assertThat(hasSignal(compat, "일지", "육합")).isTrue();
        assertThat(compat.positives()).isNotEmpty();
        assertThat(compat.score()).isGreaterThan(62);   // 기본값보다 높다
    }

    @Test
    void 일지_충이면_나쁜_신호로_잡히고_점수가_내려간다() {
        // 자-오 충
        FourPillars a = withDayBranch(EarthlyBranch.JA, 1990);
        FourPillars b = withDayBranch(EarthlyBranch.O, 1992);

        Compatibility compat = analyzer.analyze(a, b);

        assertThat(hasSignal(compat, "일지", "충")).isTrue();
        assertThat(compat.negatives()).isNotEmpty();
        assertThat(compat.score()).isLessThan(62);
    }

    @Test
    void 일지_삼합은_오행까지_설명에_들어간다() {
        // 신-자 삼합 = 수국
        FourPillars a = withDayBranch(EarthlyBranch.SIN, 1990);
        FourPillars b = withDayBranch(EarthlyBranch.JA, 1992);

        Compatibility compat = analyzer.analyze(a, b);

        CompatSignal samhap = compat.signals().stream()
                .filter(s -> s.position().equals("일지") && s.relation().equals("삼합"))
                .findFirst().orElseThrow();
        assertThat(samhap.detail()).contains("수국");
        assertThat(samhap.weight()).isPositive();
    }

    @Test
    void 순서를_바꿔도_점수는_같다() {
        FourPillars a = calculator.calculate(LocalDate.of(1997, 11, 28), null, Gender.MALE, TODAY);
        FourPillars b = calculator.calculate(LocalDate.of(1998, 3, 14), null, Gender.FEMALE, TODAY);

        assertThat(analyzer.analyze(a, b).score()).isEqualTo(analyzer.analyze(b, a).score());
    }

    @Test
    void 십성_관계는_항상_들어간다() {
        FourPillars a = calculator.calculate(LocalDate.of(1997, 11, 28), null, Gender.MALE, TODAY);
        FourPillars b = calculator.calculate(LocalDate.of(1998, 3, 14), null, Gender.FEMALE, TODAY);

        Compatibility compat = analyzer.analyze(a, b);

        assertThat(compat.aSeesB()).isNotNull();
        assertThat(compat.bSeesA()).isNotNull();
        assertThat(hasSignal(compat, "일간", "십성")).isTrue();
    }

    @Test
    void 점수는_30에서_98_사이로_묶인다() {
        LocalDate base = LocalDate.of(1990, 1, 1);
        for (int i = 0; i < 120; i += 7) {
            for (int j = 0; j < 120; j += 11) {
                FourPillars a = calculator.calculate(base.plusDays(i), null, Gender.MALE, TODAY);
                FourPillars b = calculator.calculate(base.plusDays(j), null, Gender.FEMALE, TODAY);
                assertThat(analyzer.analyze(a, b).score()).isBetween(30, 98);
            }
        }
    }

    @Test
    void 없는_오행을_상대가_채워주면_보완_신호가_뜬다() {
        // 오행이 한쪽으로 쏠린 사주끼리 맞춰보면 보완 신호가 나오는 조합이 있다
        LocalDate base = LocalDate.of(1990, 1, 1);
        boolean found = false;
        for (int i = 0; i < 400 && !found; i++) {
            FourPillars a = calculator.calculate(base.plusDays(i), null, Gender.MALE, TODAY);
            if (a.missingElements().isEmpty()) continue;
            for (int j = 0; j < 400; j += 3) {
                FourPillars b = calculator.calculate(base.plusDays(j), null, Gender.FEMALE, TODAY);
                Compatibility compat = analyzer.analyze(a, b);
                if (!compat.aFilledByB().isEmpty()) {
                    assertThat(compat.signals())
                            .anyMatch(s -> s.relation().equals("보완") && s.weight() > 0);
                    found = true;
                    break;
                }
            }
        }
        assertThat(found).isTrue();
    }
}
