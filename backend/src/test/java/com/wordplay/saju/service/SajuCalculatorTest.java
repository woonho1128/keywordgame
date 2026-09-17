package com.wordplay.saju.service;

import com.wordplay.saju.domain.Element;
import com.wordplay.saju.domain.FourPillars;
import com.wordplay.saju.domain.Gender;
import com.wordplay.saju.domain.LuckCycle;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SajuCalculatorTest {

    /** 진태양시 보정 -30분 (서비스 기본값) */
    private final SajuCalculator calculator = new SajuCalculator(-30);

    /** 보정 없이 규칙만 확인하고 싶을 때 */
    private final SajuCalculator noOffset = new SajuCalculator(0);

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);

    private FourPillars calc(LocalDate date, LocalTime time) {
        return calculator.calculate(date, time, Gender.MALE, TODAY);
    }

    @Test
    void 연주는_1월1일이_아니라_입춘에_바뀐다() {
        // 2024년 입춘은 2/4 17:27 — 그 전은 아직 계묘년
        assertThat(calc(LocalDate.of(2024, 1, 20), null).year().korean()).isEqualTo("계묘");
        assertThat(calc(LocalDate.of(2024, 2, 4), null).year().korean()).isEqualTo("계묘");
        assertThat(calc(LocalDate.of(2024, 2, 5), null).year().korean()).isEqualTo("갑진");
        assertThat(calc(LocalDate.of(2024, 6, 1), null).year().korean()).isEqualTo("갑진");
    }

    @Test
    void 연주_육십갑자_알려진_해() {
        assertThat(calc(LocalDate.of(1984, 6, 1), null).year().korean()).isEqualTo("갑자");
        assertThat(calc(LocalDate.of(1988, 6, 1), null).year().korean()).isEqualTo("무진");
        assertThat(calc(LocalDate.of(2000, 6, 1), null).year().korean()).isEqualTo("경진");
        assertThat(calc(LocalDate.of(2026, 6, 1), null).year().korean()).isEqualTo("병오");
    }

    @Test
    void 일주는_1949년_10월_1일이_갑자일() {
        // 만세력 검증용 기준점
        assertThat(calc(LocalDate.of(1949, 10, 1), null).day().korean()).isEqualTo("갑자");
        assertThat(calc(LocalDate.of(1949, 10, 2), null).day().korean()).isEqualTo("을축");
        assertThat(calc(LocalDate.of(1949, 9, 30), null).day().korean()).isEqualTo("계해");
        // 60일 주기
        assertThat(calc(LocalDate.of(1949, 11, 30), null).day().korean()).isEqualTo("갑자");
        assertThat(calc(LocalDate.of(2024, 1, 1), null).day().korean()).isEqualTo("갑자");
    }

    @Test
    void 월주_천간은_년간에서_오호둔으로_나온다() {
        // 갑진년(2024) 묘월(경칩 3/5 이후) → 정묘월
        FourPillars p = calc(LocalDate.of(2024, 3, 10), null);
        assertThat(p.month().korean()).isEqualTo("정묘");
        assertThat(p.monthTermName()).isEqualTo("경칩");

        // 갑년의 인월은 병인
        assertThat(calc(LocalDate.of(2024, 2, 20), null).month().korean()).isEqualTo("병인");
        // 을년(2025)의 인월은 무인
        assertThat(calc(LocalDate.of(2025, 2, 20), null).month().korean()).isEqualTo("무인");
    }

    @Test
    void 월주는_절기_전이면_전월지지를_쓴다() {
        // 경칩(3/5) 직전은 아직 인월
        assertThat(calc(LocalDate.of(2024, 3, 1), null).month().branch().korean()).isEqualTo("인");
        assertThat(calc(LocalDate.of(2024, 3, 10), null).month().branch().korean()).isEqualTo("묘");
        // 소한(1/6) 전은 전년 자월
        assertThat(calc(LocalDate.of(2024, 1, 3), null).month().branch().korean()).isEqualTo("자");
    }

    @Test
    void 시주_천간은_일간에서_오자둔으로_나온다() {
        // 2024-01-01은 갑자일 → 갑일의 자시는 갑자시
        FourPillars p = noOffset.calculate(LocalDate.of(2024, 1, 1), LocalTime.of(0, 30),
                Gender.MALE, TODAY);
        assertThat(p.day().korean()).isEqualTo("갑자");
        assertThat(p.hour().korean()).isEqualTo("갑자");

        // 같은 날 오시(11~13시) → 갑일의 오시는 경오시
        FourPillars noon = noOffset.calculate(LocalDate.of(2024, 1, 1), LocalTime.of(12, 0),
                Gender.MALE, TODAY);
        assertThat(noon.hour().korean()).isEqualTo("경오");
    }

    @Test
    void 밤_11시_이후는_다음날_일주로_본다() {
        FourPillars p = noOffset.calculate(LocalDate.of(2024, 1, 1), LocalTime.of(23, 30),
                Gender.MALE, TODAY);
        assertThat(p.day().korean()).isEqualTo("을축");   // 갑자일의 다음 날
        assertThat(p.hour().korean()).isEqualTo("병자");  // 을일의 자시
    }

    @Test
    void 진태양시_보정이_시주_경계를_바꾼다() {
        // 01:10 은 축시지만 -30분 보정하면 00:40 → 자시
        LocalDate date = LocalDate.of(2024, 1, 1);
        assertThat(noOffset.calculate(date, LocalTime.of(1, 10), Gender.MALE, TODAY)
                .hour().branch().korean()).isEqualTo("축");
        assertThat(calculator.calculate(date, LocalTime.of(1, 10), Gender.MALE, TODAY)
                .hour().branch().korean()).isEqualTo("자");
    }

    @Test
    void 출생시각을_모르면_시주가_없고_여섯글자만_센다() {
        FourPillars p = calc(LocalDate.of(1990, 5, 15), null);
        assertThat(p.hourKnown()).isFalse();
        assertThat(p.hour()).isNull();
        assertThat(p.elementCounts().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(6);

        FourPillars withHour = calc(LocalDate.of(1990, 5, 15), LocalTime.of(10, 30));
        assertThat(withHour.elementCounts().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(8);
    }

    @Test
    void 대운은_양남음녀_순행_음남양녀_역행() {
        LocalDate date = LocalDate.of(2024, 6, 1);   // 갑진년 = 양간
        assertThat(calculator.calculate(date, null, Gender.MALE, TODAY).forwardLuck()).isTrue();
        assertThat(calculator.calculate(date, null, Gender.FEMALE, TODAY).forwardLuck()).isFalse();

        LocalDate yin = LocalDate.of(2025, 6, 1);    // 을사년 = 음간
        assertThat(calculator.calculate(yin, null, Gender.MALE, TODAY).forwardLuck()).isFalse();
        assertThat(calculator.calculate(yin, null, Gender.FEMALE, TODAY).forwardLuck()).isTrue();
    }

    @Test
    void 대운은_월주에서_한칸씩_옮겨간다() {
        // 갑진년 묘월(정묘) 남자 → 순행이므로 무진, 기사, 경오...
        FourPillars forward = calculator.calculate(
                LocalDate.of(2024, 3, 10), null, Gender.MALE, TODAY);
        assertThat(forward.month().korean()).isEqualTo("정묘");
        assertThat(forward.luckCycles()).extracting(c -> c.pillar().korean())
                .startsWith("무진", "기사", "경오");

        // 같은 사주 여자 → 역행이므로 병인, 을축, 갑자...
        FourPillars backward = calculator.calculate(
                LocalDate.of(2024, 3, 10), null, Gender.FEMALE, TODAY);
        assertThat(backward.luckCycles()).extracting(c -> c.pillar().korean())
                .startsWith("병인", "을축", "갑자");
    }

    @Test
    void 대운수는_절입까지의_날수를_3으로_나눈값() {
        // 2024-03-10(순행) → 다음 절입 청명 4/4 까지 25일 → 25/3 = 8 나머지 1 → 버림 → 8
        FourPillars p = calculator.calculate(LocalDate.of(2024, 3, 10), null, Gender.MALE, TODAY);
        assertThat(p.luckStartAge()).isEqualTo(8);
        assertThat(p.luckCycles()).extracting(LuckCycle::startAge)
                .startsWith(8, 18, 28);

        // 같은 날 역행 → 지난 절입 경칩 3/5 부터 5일 → 5/3 = 1 나머지 2 → 올림 → 2
        FourPillars back = calculator.calculate(LocalDate.of(2024, 3, 10), null, Gender.FEMALE, TODAY);
        assertThat(back.luckStartAge()).isEqualTo(2);
    }

    @Test
    void 현재_대운은_세는나이로_고른다() {
        FourPillars p = calculator.calculate(LocalDate.of(1990, 5, 15), null, Gender.MALE, TODAY);
        assertThat(p.koreanAge()).isEqualTo(37);   // 2026 - 1990 + 1

        LuckCycle current = p.currentLuck();
        assertThat(current).isNotNull();
        assertThat(p.koreanAge()).isBetween(current.startAge(), current.endAge());
    }

    @Test
    void 세운은_기준연도_간지() {
        FourPillars p = calculator.calculate(LocalDate.of(1990, 5, 15), null, Gender.MALE, TODAY);
        assertThat(p.yearlyLuckYear()).isEqualTo(2026);
        assertThat(p.yearlyLuck().korean()).isEqualTo("병오");
    }

    @Test
    void 오행분포와_띠() {
        // 1990-05-15 → 경오년 신사월 (경오년 = 말띠)
        FourPillars p = calc(LocalDate.of(1990, 5, 15), null);
        assertThat(p.year().korean()).isEqualTo("경오");
        assertThat(p.zodiac()).isEqualTo("말");
        assertThat(p.elementCounts().get(Element.METAL)).isPositive();
        assertThat(p.strongestElement()).isNotNull();
    }

    @Test
    void 지원하지_않는_연도는_거부한다() {
        assertThatThrownBy(() -> calc(LocalDate.of(1800, 1, 1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calc(LocalDate.of(2200, 1, 1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
