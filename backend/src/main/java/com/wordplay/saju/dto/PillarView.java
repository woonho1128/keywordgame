package com.wordplay.saju.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.wordplay.saju.domain.HeavenlyStem;
import com.wordplay.saju.domain.Pillar;
import com.wordplay.saju.domain.TenGod;

import java.util.List;

/** 기둥 하나를 화면에 뿌리기 위한 형태 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PillarView(
        String position,
        String stem,
        String stemHanja,
        String stemElement,
        String branch,
        String branchHanja,
        String branchElement,
        String zodiac,
        String stemTenGod,
        String branchTenGod,
        List<String> hiddenStems
) {

    /** @param dayMaster 십성 판정 기준이 되는 일간. 일주의 천간은 십성 대신 "일간"으로 표시한다. */
    public static PillarView of(String position, Pillar pillar, HeavenlyStem dayMaster, boolean isDayPillar) {
        return new PillarView(
                position,
                pillar.stem().korean(),
                pillar.stem().hanja(),
                pillar.stem().element().korean(),
                pillar.branch().korean(),
                pillar.branch().hanja(),
                pillar.branch().element().korean(),
                pillar.branch().zodiac(),
                isDayPillar ? "일간" : TenGod.of(dayMaster, pillar.stem()).korean(),
                TenGod.of(dayMaster, pillar.branch()).korean(),
                pillar.branch().hiddenStems().stream().map(HeavenlyStem::korean).toList()
        );
    }
}
