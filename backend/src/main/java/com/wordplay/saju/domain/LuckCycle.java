package com.wordplay.saju.domain;

/**
 * 대운(大運) 한 구간 — 10년 단위로 바뀌는 운의 흐름.
 *
 * @param order     몇 번째 대운인지 (1부터)
 * @param startAge  시작 나이 (세는나이 기준 대운수)
 * @param pillar    해당 구간의 간지
 * @param current   현재 나이가 이 구간에 속하는지
 */
public record LuckCycle(int order, int startAge, Pillar pillar, boolean current) {

    public int endAge() {
        return startAge + 9;
    }
}
