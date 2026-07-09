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
        List<PlayerView> players,
        List<Integer> myBoard,   // 크기 size*size(flatten), 미설정이면 빈 리스트
        List<Integer> drawn,     // 뽑힌 숫자(순서대로)
        int lastDrawn,           // 마지막 뽑힌 숫자, 없으면 -1
        long nextDrawAt,         // 다음 뽑기 시각(ms)
        int myLines,
        int winnerSeat,
        String winnerNick,
        int playerCount,
        long version
) {
    public record PlayerView(int seat, String nick, boolean ready, int lines) {}

    public static BingoStateResponse notStarted(long now) {
        return new BingoStateResponse(
                "NOT_STARTED", now, false, false, 0, null, 5, 50, 3,
                List.of(), List.of(), List.of(), -1, 0, 0, -1, null, 0, 0);
    }
}
