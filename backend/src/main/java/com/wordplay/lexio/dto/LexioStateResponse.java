package com.wordplay.lexio.dto;

import java.util.List;

/** 렉시오 폴링 응답. status: NOT_STARTED / LOBBY / PLAYING / ROUND_END / ENDED */
public record LexioStateResponse(
        String status,
        long serverNow,
        String theme,          // BLACK / WHITE
        String scoreMode,      // SINGLE / ACCUMULATE
        boolean isHost,
        boolean joined,
        int seat,              // 1-based, 미참가 0
        String nick,
        List<PlayerView> players,
        List<Integer> myTiles, // 내 손패(타일 id)
        int currentTurnSeat,   // 1-based, 아니면 -1
        boolean myTurn,
        List<Integer> tableHand, // 현재 테이블 조합(타일 id), 없으면 빈 리스트
        int tableSeat,           // 그 조합 낸 좌석(1-based), 없으면 -1
        String tableHandLabel,   // 조합 이름(싱글/원페어/…)
        long turnEndsAt,         // 현재 차례 마감 시각(ms)
        int mustIncludeTile,     // 내 차례+첫 선일 때 반드시 포함할 타일 id, 아니면 -1
        String lastAction,
        int roundWinnerSeat,     // 이번 판 승자(1-based), 없으면 -1
        int gameWinnerSeat,      // 게임 승자(단판, 1-based), 없으면 -1
        int playerCount,
        long version
) {
    public record PlayerView(int seat, String nick, boolean bot, int tileCount, int score, boolean out, boolean passed) {}

    public static LexioStateResponse notStarted(long now) {
        return new LexioStateResponse(
                "NOT_STARTED", now, "BLACK", "SINGLE", false, false, 0, null,
                List.of(), List.of(), -1, false, List.of(), -1, null, 0, -1, null, -1, -1, 0, 0);
    }
}
