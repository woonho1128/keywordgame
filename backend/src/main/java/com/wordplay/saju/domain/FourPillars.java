package com.wordplay.saju.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /** 일간을 뺀 나머지 글자들 — 십성을 셀 때 자기 자신은 빼고 본다 */
    private List<TenGod> otherTenGods() {
        List<TenGod> gods = new ArrayList<>(7);
        HeavenlyStem me = dayMaster();
        for (Pillar p : new Pillar[]{year, month, hour}) {
            if (p != null) gods.add(TenGod.of(me, p.stem()));
        }
        for (Pillar p : new Pillar[]{year, month, day, hour}) {
            if (p != null) gods.add(TenGod.of(me, p.branch()));
        }
        return gods;
    }

    /**
     * 십성 그룹별 개수 — 비겁·식상·재성·관성·인성.
     * 일간(자기 자신)은 빼고, 지지는 지장간 정기로 센다.
     */
    public Map<String, Integer> tenGodGroupCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String group : List.of("비겁", "식상", "재성", "관성", "인성")) counts.put(group, 0);
        for (TenGod god : otherTenGods()) counts.merge(god.group(), 1, Integer::sum);
        return counts;
    }

    /** 자리 이름과 기둥을 짝지어 돌려준다 (시주를 모르면 셋) */
    public List<Map.Entry<String, Pillar>> positionedPillars() {
        List<Map.Entry<String, Pillar>> list = new ArrayList<>(4);
        list.add(Map.entry("연주", year));
        list.add(Map.entry("월주", month));
        list.add(Map.entry("일주", day));
        if (hour != null) list.add(Map.entry("시주", hour));
        return list;
    }

    /**
     * 득령(得令) — 태어난 달(월지)이 일간을 밀어주는지.
     * 월지가 일간과 같은 오행이거나 일간을 생해주면 득령으로 본다.
     */
    public boolean hasMonthSupport() {
        Element me = dayMaster().element();
        Element monthElement = month.branch().element();
        return monthElement == me || monthElement.generates() == me;
    }

    /**
     * 신강·신약 <b>간이</b> 판정.
     *
     * <p>일간을 돕는 세력(비겁+인성, 득령이면 가산)과 빼가는 세력(식상+재성+관성)을 견준다.
     * 실제 명리에서는 지장간 전체·투간·통근·합충까지 따지므로 이건 참고용 요약이다.
     */
    public String bodyStrength() {
        Map<String, Integer> counts = tenGodGroupCounts();
        int support = counts.get("비겁") + counts.get("인성") + (hasMonthSupport() ? 2 : 0);
        int drain = counts.get("식상") + counts.get("재성") + counts.get("관성");
        if (support >= drain + 2) return "신강";
        if (drain >= support + 2) return "신약";
        return "중화";
    }
}
