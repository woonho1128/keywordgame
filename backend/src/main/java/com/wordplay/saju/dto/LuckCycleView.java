package com.wordplay.saju.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.wordplay.saju.domain.LuckCycle;

/** 대운 한 구간 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LuckCycleView(
        int order,
        int startAge,
        int endAge,
        String pillar,
        String pillarHanja,
        boolean current
) {

    public static LuckCycleView from(LuckCycle cycle) {
        return new LuckCycleView(
                cycle.order(),
                cycle.startAge(),
                cycle.endAge(),
                cycle.pillar().korean(),
                cycle.pillar().hanja(),
                cycle.current()
        );
    }
}
