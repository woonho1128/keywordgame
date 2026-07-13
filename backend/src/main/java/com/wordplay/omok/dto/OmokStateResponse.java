package com.wordplay.omok.dto;

import java.util.List;

/** 오목 상태 응답. color: 1=흑(선), 2=백. board: 15×15=225칸. */
public record OmokStateResponse(
        String status,          // NULL_ROOM / LOBBY / PLAYING / ENDED
        long serverNow,
        boolean isHost,
        boolean joined,
        int seat,
        String nick,
        int myColor,
        int hostColor,
        String rule,            // FREE / RENJU
        List<PlayerView> players,
        List<Integer> board,
        int currentColor,       // 0 진행중 아님
        boolean myTurn,
        int lastMove,           // -1 없음
        int winner,             // 0 미정 / 1 흑 / 2 백 / 3 무
        List<Integer> winLine,  // 승리 5칸(하이라이트)
        List<Integer> forbidden,// 금수 자리(내 차례·흑·렌주)
        String lastAction,
        int playerCount,
        long version
) {
    public record PlayerView(int seat, String nick, boolean ai, int color) {}

    public static OmokStateResponse notStarted(long now) {
        return new OmokStateResponse("NULL_ROOM", now, false, false, 0, null, 0, 1, "FREE",
                List.of(), List.of(), 0, false, -1, 0, List.of(), List.of(), null, 0, 0);
    }
}
