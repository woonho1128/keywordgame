package com.wordplay.saju.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.wordplay.saju.domain.CompatSignal;
import com.wordplay.saju.domain.Compatibility;
import com.wordplay.saju.domain.Element;
import com.wordplay.saju.domain.FourPillars;

import java.util.List;

/**
 * 계산된 궁합 근거 — 화면에 그대로 보여주고 AI 프롬프트에도 넣는다.
 * DB에 JSON으로 저장되므로 필드를 지울 땐 과거 기록 호환을 확인할 것.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompatAnalysis(
        int score,
        String aName,
        String bName,
        SajuChart aChart,
        SajuChart bChart,
        String aSeesB,
        String bSeesA,
        String dayStemHap,
        List<String> aFilledByB,
        List<String> bFilledByA,
        List<SignalView> signals
) {

    public record SignalView(String position, String relation, String detail, boolean positive) {

        static SignalView from(CompatSignal signal) {
            return new SignalView(signal.position(), signal.relation(), signal.detail(), signal.positive());
        }
    }

    public static CompatAnalysis of(String aName, FourPillars a,
                                    String bName, FourPillars b,
                                    Compatibility compat) {
        return new CompatAnalysis(
                compat.score(),
                aName,
                bName,
                SajuChart.from(a),
                SajuChart.from(b),
                compat.aSeesB().korean(),
                compat.bSeesA().korean(),
                compat.dayStemHapElement() == null ? null : compat.dayStemHapElement().korean(),
                compat.aFilledByB().stream().map(Element::korean).toList(),
                compat.bFilledByA().stream().map(Element::korean).toList(),
                compat.signals().stream().map(SignalView::from).toList()
        );
    }
}
