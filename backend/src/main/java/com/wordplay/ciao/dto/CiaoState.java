package com.wordplay.ciao.dto;

import java.util.List;

/**
 * 차오차오 상태 응답. 주사위 실제 값(myRoll)은 굴린 본인에게만 채워 보낸다(0 = X).
 * 의심 없이 지나간 턴의 실제 값은 영원히 공개하지 않는다(블러핑 유지).
 */
public record CiaoState(
        String phase,                 // LOBBY / PLAYING / ENDED
        String turnPhase,             // ROLL / DECLARE / CHALLENGE (PLAYING 중에만)
        int bridgeLen,                // 다리 칸 수(10)
        int pawnsPer,                 // 인당 말 개수(인원 보정)
        int goal,                     // 승리에 필요한 건넌 말 수(인원 보정)
        int challengeSec,             // 의심 대기 시간(초). 0이면 제한 없음
        boolean noTimeLimit,          // 제한시간 없음 모드
        int passedCount,              // 이번 의심 창에서 '통과'한 사람 수
        int challengerCount,          // 의심할 수 있는 사람 수
        boolean iPassed,              // 내가 이미 통과했는가
        boolean isHost,
        boolean joined,
        List<PlayerView> players,
        int turnSeat,
        String turnName,
        boolean myTurn,
        int mySeat,
        int declared,                 // 현재 선언 값(1~4), 없으면 -1
        int myRoll,                   // 내가 굴린 실제 값(1~4, 0=X) — 굴린 본인에게만. 아니면 -1
        boolean canChallenge,         // 내가 지금 의심 버튼을 누를 수 있는가
        Reveal lastReveal,            // 직전 의심 판정(공개 연출용). 없으면 null
        String lastAction,
        List<String> log,
        int winnerSeat,
        String winnerLabel,
        long deadline,                // 현재 단계 마감(ROLL/DECLARE 턴 제한 또는 CHALLENGE 창 마감)
        long serverNow
) {
    public record PlayerView(int seat, String name, boolean bot, boolean host, boolean me,
                             boolean left, boolean eliminated, int pawnsLeft, int crossed, int bridgePos) {}
    /** 의심 판정 결과: actual 0=X. lie=true면 선언자가 거짓말. */
    public record Reveal(int seat, int challengerSeat, int declared, int actual, boolean lie) {}

    public static CiaoState notFound(long now) {
        return new CiaoState("NONE", null, 10, 0, 0, 8, false, 0, 0, false, false, false, List.of(),
                -1, null, false, -1, -1, -1, false, null, null, List.of(), -1, null, 0, now);
    }
}
