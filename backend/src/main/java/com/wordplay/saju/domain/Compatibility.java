package com.wordplay.saju.domain;

import java.util.List;

/**
 * 두 사주를 맞대본 결과. 전부 계산으로 나온 값이고, AI는 이걸 말로 풀기만 한다.
 *
 * @param signals              판정 근거 목록 (자리별 합·충 등)
 * @param score                궁합 점수 (가중치 합산, 30~98)
 * @param aSeesB               A의 일간이 보는 B 일간의 십성
 * @param bSeesA               B의 일간이 보는 A 일간의 십성
 * @param aFilledByB           A에게 없는 오행 중 B가 넉넉히 가진 것
 * @param bFilledByA           B에게 없는 오행 중 A가 넉넉히 가진 것
 * @param dayStemHapElement    두 일간이 합을 이루면 그 결과 오행 (아니면 null)
 */
public record Compatibility(
        List<CompatSignal> signals,
        int score,
        TenGod aSeesB,
        TenGod bSeesA,
        List<Element> aFilledByB,
        List<Element> bFilledByA,
        Element dayStemHapElement
) {

    public List<CompatSignal> positives() {
        return signals.stream().filter(CompatSignal::positive).toList();
    }

    public List<CompatSignal> negatives() {
        return signals.stream().filter(s -> !s.positive()).toList();
    }
}
