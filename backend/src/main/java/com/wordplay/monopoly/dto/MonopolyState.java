package com.wordplay.monopoly.dto;

import java.util.List;

/** 부루마블 상태 응답. 카드 내용은 뽑은 본인에게만 채워 보낸다. */
public record MonopolyState(
        String phase,                 // LOBBY / PLAYING / ENDED
        boolean isHost,
        boolean joined,
        List<PlayerView> players,
        List<TileView> board,
        int turnSeat,
        String turnName,
        boolean myTurn,
        int mySeat,
        int[] dice,
        boolean lastDouble,
        String step,                  // ROLL / DECIDE
        Pending pending,              // 결정 대기(카드는 소유자에게만)
        long pot,
        String lastAction,
        List<String> log,
        int winnerSeat,
        String winnerLabel,
        long deadline,
        long serverNow
) {
    public record PlayerView(int seat, String name, boolean bot, boolean host, boolean me,
                             int color, long cash, int pos, boolean alive, boolean inIsland, boolean left, int props) {}

    /** 칸 상태. name/type/group/price는 정적, ownerSeat/tier/festival은 동적. */
    public record TileView(int index, String name, String type, String group, int price,
                           int ownerSeat, int tier, boolean festival) {}

    /** 현재 차례 플레이어가 해야 할 결정. type=NONE이면 없음. */
    public record Pending(String type, int tile, long toll, int buyPrice, int upgradeCost,
                          int takeoverCost, boolean canBuild, List<Integer> travelOptions, Card card) {}

    public record Card(String icon, String title, String desc) {}

    public static MonopolyState notFound(long now) {
        return new MonopolyState("NONE", false, false, List.of(), List.of(), -1, null, false, -1,
                new int[]{0, 0}, false, "ROLL", null, 0, null, List.of(), -1, null, 0, now);
    }
}
