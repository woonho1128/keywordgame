package com.wordplay.saju.dto;

import com.wordplay.saju.domain.Element;
import com.wordplay.saju.domain.FourPillars;
import com.wordplay.saju.domain.HeavenlyStem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 계산된 사주팔자 — AI 해석의 입력이자 화면에 그대로 보여주는 "사실" 데이터.
 * DB에 JSON으로 저장되므로 필드를 지울 땐 과거 기록 호환을 확인할 것.
 */
public record SajuChart(
        List<PillarView> pillars,
        String dayMaster,
        String dayMasterHanja,
        String dayMasterElement,
        String zodiac,
        int koreanAge,
        boolean hourKnown,
        Map<String, Integer> elementCounts,
        List<String> missingElements,
        String strongestElement,
        boolean forwardLuck,
        int luckStartAge,
        List<LuckCycleView> luckCycles,
        String currentLuck,
        String yearlyLuck,
        int yearlyLuckYear
) {

    public static SajuChart from(FourPillars p) {
        HeavenlyStem dayMaster = p.dayMaster();

        List<PillarView> pillars = new ArrayList<>(4);
        pillars.add(PillarView.of("연주", p.year(), dayMaster, false));
        pillars.add(PillarView.of("월주", p.month(), dayMaster, false));
        pillars.add(PillarView.of("일주", p.day(), dayMaster, true));
        if (p.hourKnown()) {
            pillars.add(PillarView.of("시주", p.hour(), dayMaster, false));
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        p.elementCounts().forEach((element, count) -> counts.put(element.korean(), count));

        var current = p.currentLuck();

        return new SajuChart(
                pillars,
                dayMaster.korean(),
                dayMaster.hanja(),
                dayMaster.element().korean(),
                p.zodiac(),
                p.koreanAge(),
                p.hourKnown(),
                counts,
                p.missingElements().stream().map(Element::korean).toList(),
                p.strongestElement().korean(),
                p.forwardLuck(),
                p.luckStartAge(),
                p.luckCycles().stream().map(LuckCycleView::from).toList(),
                current == null ? null : current.pillar().display(),
                p.yearlyLuck().display(),
                p.yearlyLuckYear()
        );
    }
}
