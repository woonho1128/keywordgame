package com.wordplay.saju.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 사주팔자 계산 결과 묶음. AI 해석의 입력이 되는 "사실" 데이터.
 *
 * @param year            연주
 * @param month           월주
 * @param day             일주 (일간이 곧 나 자신)
 * @param hour            시주 — 출생시각을 모르면 null
 * @param solarYear       입춘 기준 연도 (1월~입춘 전 출생은 전년도)
 * @param monthTermName   월주를 연 절기 이름 (예: 입춘)
 * @param adjustedBirth   진태양시 보정 후 출생시각 (시간 모르면 정오 가정)
 * @param hourKnown       출생시각을 알고 있는지
 * @param elementCounts   오행 분포 (시간 모르면 6글자, 알면 8글자 기준)
 * @param forwardLuck     대운 순행 여부
 * @param luckStartAge    대운수 (첫 대운이 시작되는 나이)
 * @param luckCycles      대운 목록
 * @param yearlyLuck      세운 (기준 연도의 간지)
 * @param yearlyLuckYear  세운 기준 연도
 * @param koreanAge       세는나이
 */
public record FourPillars(
        Pillar year,
        Pillar month,
        Pillar day,
        Pillar hour,
        int solarYear,
        String monthTermName,
        LocalDateTime adjustedBirth,
        boolean hourKnown,
        Map<Element, Integer> elementCounts,
        boolean forwardLuck,
        int luckStartAge,
        List<LuckCycle> luckCycles,
        Pillar yearlyLuck,
        int yearlyLuckYear,
        int koreanAge
) {

    /** 일간(日干) — 사주에서 "나" 자신을 뜻한다. 십성 판정의 기준. */
    public HeavenlyStem dayMaster() {
        return day.stem();
    }

    /** 띠 */
    public String zodiac() {
        return year.branch().zodiac();
    }

    /** 현재 진행 중인 대운 (대운 시작 전이면 null) */
    public LuckCycle currentLuck() {
        return luckCycles.stream().filter(LuckCycle::current).findFirst().orElse(null);
    }

    /** 가장 많은 오행 */
    public Element strongestElement() {
        return elementCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(Element.EARTH);
    }

    /** 사주에 아예 없는 오행 */
    public List<Element> missingElements() {
        return elementCounts.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .map(Map.Entry::getKey)
                .toList();
    }
}
