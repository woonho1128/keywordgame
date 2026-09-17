package com.wordplay.coup.dto;

import java.util.List;

/** 쿠 상태 응답. 정보 격리: myCards만 내 정체 공개, 남은 공개된 카드만 노출. */
public record CoupStateResponse(
        String status,          // NULL_ROOM / LOBBY / PLAYING / ENDED
        long serverNow,
        int reactionSec,
        boolean isHost,
        boolean joined,
        int seat,
        String nick,

        List<PlayerView> players,
        List<String> myCards,   // 내 카드(캐릭터명, 공개된 건 null)
        int currentSeat,
        String step,
        PendingView pending,

        boolean myTurn,
        boolean canRespond,
        boolean canChallenge,
        boolean canBlock,
        List<String> blockOptions,
        boolean mustLose,
        boolean mustExchange,
        List<String> exchangeOptions,
        int exchangeKeep,

        List<String> log,
        int winnerSeat,
        int playerCount,
        long version
) {
    /** cards: 각 카드 캐릭터명(공개된 것만), 숨은 건 null. influence=살아있는 카드 수. */
    public record PlayerView(int seat, String nick, boolean bot, int coins, boolean alive,
                             int influence, List<String> cards, boolean current) {}

    public record PendingView(String step, int actorSeat, String action, int targetSeat,
                              String claimChar, int blockerSeat, String blockChar, int loserSeat, long deadline) {}

    public static CoupStateResponse notStarted(long now) {
        return new CoupStateResponse("NULL_ROOM", now, 15, false, false, -1, null,
                List.of(), List.of(), 0, "CHOOSE_ACTION", null,
                false, false, false, false, List.of(), false, false, List.of(), 0,
                List.of(), -1, 0, 0);
    }
}
