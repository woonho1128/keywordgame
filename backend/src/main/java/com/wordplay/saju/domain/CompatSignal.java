package com.wordplay.saju.domain;

/**
 * 궁합 판정 근거 하나. 계산으로 나온 사실이라 AI가 지어낸 게 아니다.
 *
 * @param position 어느 자리에서 나온 관계인지 (일지·년지·월지·시지·일간)
 * @param relation 관계 이름 (육합·삼합·충·형·해·파·합)
 * @param detail   사람이 읽을 설명
 * @param weight   점수 가중치 (양수면 좋은 쪽)
 */
public record CompatSignal(String position, String relation, String detail, int weight) {

    public boolean positive() {
        return weight > 0;
    }
}
