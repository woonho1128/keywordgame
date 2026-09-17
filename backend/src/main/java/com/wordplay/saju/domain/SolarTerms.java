package com.wordplay.saju.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 12절(節) 절입 시각 계산 — 사주의 월주(月柱)는 달력 월이 아니라 절기로 나뉜다.
 *
 * <p>태양의 겉보기 황경(apparent longitude)이 특정 각도에 도달하는 순간을 이분법으로 찾는다.
 * 황경 계산은 Meeus 의 저정밀 태양 위치 공식(오차 약 0.01도 ≈ 시간으로 15분)을 쓴다.
 * 절입 순간에서 15분 이내에 태어난 극단적인 경우가 아니면 결과가 갈리지 않는다.
 *
 * <p>근사식(寿星공식) 대신 이 방식을 쓰는 이유: 근사식은 베이징 기준 "일(日)" 단위라
 * 한국시(UTC+9)에서 자정 근처 절입이 하루씩 어긋난다. (예: 2024년 대설은 KST 12/7 00:17)
 *
 * <p>ΔT(역학시-세계시 차이, 현대 기준 약 70초)는 무시한다.
 */
public final class SolarTerms {

    /** 한국 표준시 오프셋 (일 단위) */
    private static final double KST_OFFSET_DAYS = 9.0 / 24.0;

    /** JD 2440587.5 == 1970-01-01T00:00Z */
    private static final double JD_EPOCH = 2440587.5;

    /** 월(1~12)이 시작되는 절기의 태양황경(도) */
    private static final double[] TERM_LONGITUDE = {
            0, 285, 315, 345, 15, 45, 75, 105, 135, 165, 195, 225, 255
    };

    /** 월(1~12)을 여는 절기 이름 */
    private static final String[] TERM_NAME = {
            "", "소한", "입춘", "경칩", "청명", "입하", "망종",
            "소서", "입추", "백로", "한로", "입동", "대설"
    };

    private SolarTerms() {}

    /** 해당 월을 여는 절기 이름 (1=소한 ... 12=대설) */
    public static String termName(int month) {
        return TERM_NAME[month];
    }

    /**
     * {@code year}년 {@code month}월의 절입 시각 (한국 표준시).
     * 12절은 항상 해당 월 3~9일 사이에 들어오므로 1일~17일 구간에서 이분 탐색한다.
     */
    public static LocalDateTime termStart(int year, int month) {
        double target = TERM_LONGITUDE[month];
        double lo = julianDay(LocalDateTime.of(year, month, 1, 0, 0));
        double hi = julianDay(LocalDateTime.of(year, month, 17, 0, 0));

        for (int i = 0; i < 50; i++) {
            double mid = (lo + hi) / 2;
            if (wrap180(apparentSolarLongitude(mid) - target) < 0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return fromJulianDay((lo + hi) / 2);
    }

    /** 기준 시각이 속한 절기 월의 시작 시각 (그 시각 이전의 가장 가까운 절입) */
    public static LocalDateTime currentTermStart(LocalDateTime kst) {
        LocalDateTime start = termStart(kst.getYear(), kst.getMonthValue());
        if (start.isAfter(kst)) {
            LocalDate prev = kst.toLocalDate().withDayOfMonth(1).minusMonths(1);
            start = termStart(prev.getYear(), prev.getMonthValue());
        }
        return start;
    }

    /** 기준 시각 다음 절입 시각 */
    public static LocalDateTime nextTermStart(LocalDateTime kst) {
        LocalDateTime start = termStart(kst.getYear(), kst.getMonthValue());
        if (!start.isAfter(kst)) {
            LocalDate next = kst.toLocalDate().withDayOfMonth(1).plusMonths(1);
            start = termStart(next.getYear(), next.getMonthValue());
        }
        return start;
    }

    /** 기준 시각이 속한 절기 월의 달 번호 (1~12, 곧 월지를 정하는 값) */
    public static int termMonth(LocalDateTime kst) {
        LocalDateTime start = termStart(kst.getYear(), kst.getMonthValue());
        if (start.isAfter(kst)) {
            return kst.getMonthValue() == 1 ? 12 : kst.getMonthValue() - 1;
        }
        return kst.getMonthValue();
    }

    /**
     * 태양의 겉보기 황경 (도, 0~360). Meeus, Astronomical Algorithms ch.25 저정밀 버전.
     */
    static double apparentSolarLongitude(double jd) {
        double t = (jd - 2451545.0) / 36525.0;

        // 기하평균황경 / 평균근점이각
        double l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t;
        double m = Math.toRadians(mod360(357.52911 + 35999.05029 * t - 0.0001537 * t * t));

        // 중심차(equation of center)
        double c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * Math.sin(m)
                 + (0.019993 - 0.000101 * t) * Math.sin(2 * m)
                 + 0.000289 * Math.sin(3 * m);

        // 장동(nutation) + 광행차(aberration) 보정
        double omega = Math.toRadians(mod360(125.04 - 1934.136 * t));
        return mod360(l0 + c - 0.00569 - 0.00478 * Math.sin(omega));
    }

    /** 한국시 기준 LocalDateTime → 율리우스일 */
    static double julianDay(LocalDateTime kst) {
        return kst.toLocalDate().toEpochDay() + JD_EPOCH
                + kst.toLocalTime().toSecondOfDay() / 86400.0
                - KST_OFFSET_DAYS;
    }

    /** 율리우스일 → 한국시 기준 LocalDateTime (초 단위 반올림) */
    static LocalDateTime fromJulianDay(double jd) {
        double epochDays = jd - JD_EPOCH + KST_OFFSET_DAYS;
        long day = (long) Math.floor(epochDays);
        long second = Math.round((epochDays - day) * 86400.0);
        if (second >= 86400) {
            day++;
            second -= 86400;
        }
        return LocalDateTime.of(LocalDate.ofEpochDay(day), LocalTime.ofSecondOfDay(second));
    }

    private static double mod360(double deg) {
        double r = deg % 360.0;
        return r < 0 ? r + 360.0 : r;
    }

    /** 각도 차이를 -180 ~ +180 으로 정규화 (0/360도 경계 대응) */
    private static double wrap180(double deg) {
        return mod360(deg + 180.0) - 180.0;
    }
}
