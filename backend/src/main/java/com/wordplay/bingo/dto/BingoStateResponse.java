package com.wordplay.bingo.dto;

import java.util.List;

/** 빙고 상태 응답. status: NOT_STARTED / LOBBY / PLAYING / ENDED */
public record BingoStateResponse(
        String status,
        long serverNow,
        boolean isHost,
        boolean joined,
        int seat,
        String nick,
        int size,
        int range,
        int target,
        String mode,             // AUTO(봇 자동) / TURN(번갈아 지목)
        List<PlayerView> players,
        List<Integer> myBoard,   // 크기 size*size(flatten), 미설정이면 빈 리스트
        List<Integer> drawn,     // 뽑힌 숫자(순서대로)
        int lastDrawn,           // 마지막 뽑힌 숫자, 없으면 -1
        long nextDrawAt,         // AUTO: 다음 뽑기 시각(ms)
        int currentTurnSeat,     // TURN: 현재 지목 차례(1-based), 아니면 -1
        boolean myTurn,          // TURN: 지금 내 차례인지
        long turnEndsAt,         // TURN: 이번 차례 마감 시각(ms)
        int myLines,
        int winnerSeat,
        String winnerNick,
        int playerCount,
        long version
) {
    public record PlayerView(int seat, String nick, boolean ready, int lines) {}

    public static BingoStateResponse notStarted(long now) {
        return new BingoStateResponse(
                "NOT_STARTED", now, false, false, 0, null, 5, 50, 3, "AUTO",
                List.of(), List.of(), List.of(), -1, 0, -1, false, 0, 0, -1, null, 0, 0);
    }
}
