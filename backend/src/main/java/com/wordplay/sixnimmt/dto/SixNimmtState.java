package com.wordplay.sixnimmt.dto;

import com.wordplay.sixnimmt.SixNimmtGame;

import java.util.List;

/** 젝스님트 상태 응답(/me · 액션 공통). */
public record SixNimmtState(
        String phase,          // LOBBY / SELECT / RESOLVE / CHOOSE_ROW / ENDED
        String endMode,        // POINTS / HANDS
        int targetHands,
        int handIndex,
        boolean isHost,
        boolean joined,
        List<List<RowCard>> rows,
        List<PlayerView> players,
        List<RowCard> myHand,
        boolean myTurnToPlay,  // SELECT이고 내가 아직 안 냄
        boolean iAmChooser,    // CHOOSE_ROW이고 내가 골라야 함
        String chooserName,    // 줄을 골라야 하는 사람
        List<SixNimmtGame.Event> events, // 직전 트릭 배치 이벤트(연출)
        String winner,
        long deadline,
        long serverNow
) {
    /** 카드 + 벌점(황소 머리). */
    public record RowCard(int card, int bulls) {}

    /** 명단/점수용 플레이어 요약(남의 손패는 노출 안 함). */
    public record PlayerView(String name, boolean bot, String botLevel, boolean host, boolean me,
                             int penalty, boolean selected, int lastTook, boolean left) {}

    public static SixNimmtState notFound(long now) {
        return new SixNimmtState("NONE", null, 0, 0, false, false,
                List.of(), List.of(), List.of(), false, false, null, List.of(), null, 0, now);
    }
}
