package com.wordplay.saju.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SolarTermsTest {

    @Test
    void 입춘_절입일_한국시() {
        assertThat(SolarTerms.termStart(2024, 2).toLocalDate()).isEqualTo(LocalDate.of(2024, 2, 4));
        assertThat(SolarTerms.termStart(2025, 2).toLocalDate()).isEqualTo(LocalDate.of(2025, 2, 3));
        assertThat(SolarTerms.termStart(2021, 2).toLocalDate()).isEqualTo(LocalDate.of(2021, 2, 3));
        assertThat(SolarTerms.termStart(2026, 2).toLocalDate()).isEqualTo(LocalDate.of(2026, 2, 4));
    }

    @Test
    void 입춘_절입_시각까지_맞는다() {
        // 실제 2024년 입춘: 2024-02-04 17:27 KST (저정밀 공식 오차 약 ±15분)
        LocalDateTime ipchun = SolarTerms.termStart(2024, 2);
        assertThat(ipchun.getHour()).isEqualTo(17);
        assertThat(ipchun.getMinute()).isBetween(12, 42);
    }

    @Test
    void 자정_근처_절입도_한국시로_판정한다() {
        // 2024년 대설은 KST 12/7 00:17 — 베이징 기준 근사식이면 12/6로 하루 밀린다
        assertThat(SolarTerms.termStart(2024, 12).toLocalDate()).isEqualTo(LocalDate.of(2024, 12, 7));
    }

    @Test
    void 절기월_판정() {
        // 입춘(2/4 17:27) 전이면 아직 1월 절기월(축월)
        assertThat(SolarTerms.termMonth(LocalDateTime.of(2024, 2, 4, 12, 0))).isEqualTo(1);
        assertThat(SolarTerms.termMonth(LocalDateTime.of(2024, 2, 4, 23, 0))).isEqualTo(2);
        assertThat(SolarTerms.termMonth(LocalDateTime.of(2024, 3, 1, 12, 0))).isEqualTo(2);
        // 경칩(3/5) 이후면 3월 절기월(묘월)
        assertThat(SolarTerms.termMonth(LocalDateTime.of(2024, 3, 10, 12, 0))).isEqualTo(3);
        // 1월 초 소한 전이면 전년 12월 절기월(자월)
        assertThat(SolarTerms.termMonth(LocalDateTime.of(2024, 1, 3, 12, 0))).isEqualTo(12);
    }

    @Test
    void 이전_다음_절입을_찾는다() {
        LocalDateTime moment = LocalDateTime.of(2024, 3, 10, 12, 0);
        assertThat(SolarTerms.currentTermStart(moment).toLocalDate()).isEqualTo(LocalDate.of(2024, 3, 5));
        assertThat(SolarTerms.nextTermStart(moment).toLocalDate()).isEqualTo(LocalDate.of(2024, 4, 4));
    }

    @Test
    void 연말연초_절입_탐색이_연도를_넘어간다() {
        assertThat(SolarTerms.nextTermStart(LocalDateTime.of(2023, 12, 20, 0, 0)).toLocalDate())
                .isEqualTo(LocalDate.of(2024, 1, 6));
        assertThat(SolarTerms.currentTermStart(LocalDateTime.of(2024, 1, 3, 0, 0)).toLocalDate())
                .isEqualTo(LocalDate.of(2023, 12, 7));
    }

    @Test
    void 율리우스일_왕복변환() {
        LocalDateTime t = LocalDateTime.of(1987, 6, 15, 13, 45);
        assertThat(SolarTerms.fromJulianDay(SolarTerms.julianDay(t))).isEqualTo(t);
    }

    @Test
    void 태양황경_춘분에_0도() {
        // 2024년 춘분: 2024-03-20 12:06 KST
        double lon = SolarTerms.apparentSolarLongitude(
                SolarTerms.julianDay(LocalDateTime.of(2024, 3, 20, 12, 6)));
        assertThat(Math.min(lon, 360 - lon)).isLessThan(0.02);
    }
}
