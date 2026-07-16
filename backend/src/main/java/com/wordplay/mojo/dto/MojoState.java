package com.wordplay.mojo.dto;

import java.util.List;

/** 모죠 상태 응답(/me · 액션 공통). */
public record MojoState(
        String phase,                 // LOBBY / PLAYING / ENDED
        boolean doublePile,
        boolean isHost,
        boolean joined,
        List<PlayerView> players,
        List<Integer> discardTops,    // 각 버림더미 맨 위 카드
        List<Integer> discardSizes,   // 각 버림더미 장수
        int drawCount,                // 남은 뽑기 더미 장수
        int turnSeat,
        String turnName,
        boolean myTurn,
        int mySeat,
        boolean myInMojo,             // 내가 모죠타임(공개 모드)인지
        boolean mustChain,            // 현재 플레이어가 같은 숫자로 이어서 내야 함
        List<Integer> myHand,         // 내 손패(정렬)
        int roundNum,
        int mojoHolderSeat,           // 모죠 카드 보유자(-1 없음)
        String winner,
        long deadline,
        long serverNow
) {
    /** front: 앞에 깐 모죠타임 카드(value=null이면 아직 비공개). */
    public record PlayerView(int seat, String name, boolean bot, boolean host, boolean me,
                             int handCount, List<FrontCard> front, boolean inMojo, int frontRevealed,
                             int total, int roundScore, boolean left, boolean hasMojo) {}

    public record FrontCard(Integer value, boolean revealed) {}

    public static MojoState notFound(long now) {
        return new MojoState("NONE", false, false, false, List.of(), List.of(), List.of(), 0,
                -1, null, false, -1, false, false, List.of(), 0, -1, null, 0, now);
    }
}
