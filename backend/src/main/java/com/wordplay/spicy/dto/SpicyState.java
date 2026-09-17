package com.wordplay.spicy.dto;

import java.util.List;

/**
 * 스파이시 상태 응답.
 * 손패(myHand)는 본인에게만 채워 보내고, 더미에 깔린 카드의 실제 값은 도전으로 공개되기 전까지 내려보내지 않는다.
 */
public record SpicyState(
        String phase,                 // LOBBY / PLAYING / ENDED
        String turnPhase,             // PLAY / CHALLENGE (PLAYING 중에만)
        int challengeSec,             // 0이면 제한 없음
        boolean noTimeLimit,          // 제한시간 없음 모드
        int passedCount,              // 이번 도전 창에서 '통과'한 사람 수
        int challengerCount,          // 도전할 수 있는 사람 수
        boolean iPassed,              // 내가 이미 통과했는가
        boolean handPenalty,
        int deckSize,                 // 이번 판 총 카드 수(인원별 확장 결과)
        int trophyTotal,              // 이번 판 트로피 개수
        boolean isHost,
        boolean joined,
        List<PlayerView> players,
        List<CardView> myHand,        // 내 손패 — 나만
        int turnSeat,
        String turnName,
        int nextSeat,               // 도전이 없을 때 다음 차례(없으면 -1)
        boolean myTurn,
        int mySeat,
        int pileSize,                 // 현재 더미에 쌓인 장수
        int declaredSpice,            // 직전 선언 향신료(0~2), 없으면 -1
        int declaredNumber,           // 직전 선언 숫자(1~10), 없으면 -1
        int lastPlayerSeat,           // 직전에 카드를 낸 사람
        List<Integer> playableNumbers,// 지금 내가 선언할 수 있는 숫자 목록
        List<Integer> playableSpices, // 지금 내가 선언할 수 있는 향신료 목록
        boolean canChallenge,         // 내가 지금 도전할 수 있는가
        Reveal lastReveal,            // 직전 도전 판정(공개 연출용)
        int drawLeft,                 // 남은 드로우 더미 장수
        int trophyLeft,
        String lastAction,
        List<String> log,
        int winnerSeat,
        String winnerLabel,
        long deadline,
        long serverNow
) {
    public record PlayerView(int seat, String name, boolean bot, boolean host, boolean me, boolean left,
                             int handCount, int won, int trophies, int score) {}
    /** spice 0~2, number 1~10. 만능 카드는 spice=-1(향신료 없음) 또는 number=-1(숫자 없음). */
    public record CardView(int id, int spice, int number) {}
    /** 도전 결과. kind: NUMBER(숫자 의심) / SPICE(향신료 의심). */
    public record Reveal(int challengerSeat, int accusedSeat, String kind,
                         int declaredSpice, int declaredNumber,
                         int actualSpice, int actualNumber,
                         boolean success, int pileTaken) {}

    public static SpicyState notFound(long now) {
        return new SpicyState("NONE", null, 8, false, 0, 0, false, false, 0, 0, false, false, List.of(), List.of(),
                -1, null, -1, false, -1, 0, -1, -1, -1, List.of(), List.of(), false, null, 0, 0,
                null, List.of(), -1, null, 0, now);
    }
}
