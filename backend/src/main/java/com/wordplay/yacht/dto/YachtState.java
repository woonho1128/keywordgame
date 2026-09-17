package com.wordplay.yacht.dto;

import java.util.List;
import java.util.Map;

/** 야찌 상태 응답(/me · 액션 공통). */
public record YachtState(
        String phase,          // LOBBY / PLAYING / ENDED
        boolean isHost,
        boolean joined,
        List<PlayerView> players,
        int turnSeat,          // 현재 차례 좌석(-1 없음)
        String turnName,
        boolean myTurn,
        int mySeat,            // 내 좌석(-1 미참가)
        List<Integer> dice,    // 현재 턴 주사위 5개
        List<Boolean> held,    // 고정 여부
        int rollsLeft,         // 남은 굴림 횟수
        boolean rolled,        // 이번 턴에 한 번이라도 굴렸는지
        String winner,
        long deadline,
        long serverNow
) {
    /** 명단/점수판용 플레이어. card: 채운 족보 key→점수, upper/total 계산값. */
    public record PlayerView(int seat, String name, boolean bot, String botLevel, boolean host, boolean me,
                             Map<String, Integer> card, int upper, int total, boolean left) {}

    public static YachtState notFound(long now) {
        return new YachtState("NONE", false, false, List.of(), -1, null, false, -1,
                List.of(), List.of(), 0, false, null, 0, now);
    }
}
