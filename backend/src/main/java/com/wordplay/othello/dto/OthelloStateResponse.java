package com.wordplay.othello.dto;

import java.util.List;

/**
 * 오델로 상태 응답. color: 1=흑(선), 2=백.
 */
public record OthelloStateResponse(
        String status,          // NULL_ROOM / LOBBY / PLAYING / ENDED
        long serverNow,
        boolean isHost,
        boolean joined,
        int seat,               // 1-based, 미참가 0
        String nick,
        int myColor,            // 1 흑, 2 백, 0 미참가
        int hostColor,
        List<PlayerView> players,
        List<Integer> board,    // 64칸, 0 빈칸 / 1 흑 / 2 백
        int currentColor,       // 0 진행중 아님
        boolean myTurn,
        List<Integer> validMoves,
        int blackCount,
        int whiteCount,
        int lastMove,           // 마지막 착수 칸(-1 없음)
        long turnEndsAt,
        String lastAction,
        int winner,             // 0 미정, 1 흑, 2 백, 3 무승부
        int playerCount,
        long version
) {
    public record PlayerView(int seat, String nick, boolean ai, int color) {}

    public static OthelloStateResponse notStarted(long now) {
        return new OthelloStateResponse(
                "NULL_ROOM", now, false, false, 0, null, 0, 1,
                List.of(), List.of(), 0, false, List.of(),
                0, 0, -1, 0, null, 0, 0, 0);
    }
}
