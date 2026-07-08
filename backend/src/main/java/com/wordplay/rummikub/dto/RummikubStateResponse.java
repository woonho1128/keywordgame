package com.wordplay.rummikub.dto;

import java.util.List;

/**
 * 루미큐브 폴링 응답.
 * status(=phase): NOT_STARTED / LOBBY / PLAYING / ENDED
 */
public record RummikubStateResponse(
        String status,
        long serverNow,
        boolean isHost,
        boolean joined,
        int seat,
        String nick,
        List<PlayerView> players,
        List<TileView> myRack,     // 나만 보임
        List<List<TileView>> table,
        int drawCount,
        int currentSeat,
        boolean isMyTurn,
        boolean myMelded,
        int winnerSeat,            // -1 없음
        String winnerNick,
        String lastAction,         // 안내 문구
        int playerCount
) {
    public record PlayerView(int seat, String nick, int rackCount, boolean melded) {}
    public record TileView(int id, String color, int number, boolean joker) {}

    public static RummikubStateResponse notStarted(long now) {
        return new RummikubStateResponse(
                "NOT_STARTED", now, false, false, 0, null, List.of(), List.of(), List.of(),
                0, 0, false, false, -1, null, null, 0);
    }
}
