package com.wordplay.halligalli.dto;

import java.util.List;

/** 할리갈리 상태 응답. status: NOT_STARTED / LOBBY / PLAYING / ENDED */
public record HalliGalliStateResponse(
        String status,
        long serverNow,
        boolean isHost,
        boolean joined,
        int seat,
        String nick,
        List<PlayerView> players,
        int currentSeat,
        boolean isMyTurn,
        int winnerSeat,
        String winnerNick,
        String lastAction,
        int playerCount,
        long version
) {
    /** 각 플레이어의 공개 정보. topFruit=null이면 공개카드 없음. */
    public record PlayerView(int seat, String nick, int down, int up, String topFruit, int topCount, boolean alive) {}

    public static HalliGalliStateResponse notStarted(long now) {
        return new HalliGalliStateResponse(
                "NOT_STARTED", now, false, false, 0, null, List.of(),
                0, false, -1, null, null, 0, 0);
    }
}
