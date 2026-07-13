package com.wordplay.horserace.dto;

import java.util.List;

/** 경마 상태 응답. 레이스는 선(先)계산 타임라인 + 서버시계 동기재생(설계 §7). */
public record HorseRaceStateResponse(
        String status,          // NULL_ROOM / LOBBY / BETTING / RACING / RESULT / ENDED
        long serverNow,
        String raceType,        // BASIC / SPECIAL
        String oddsMode,        // FIXED / PARIMUTUEL
        int round,

        boolean isHost,
        boolean joined,
        int seat,
        String nick,
        boolean isAccount,
        long chips,             // 내 테이블 칩(계정=잔고)
        boolean canBonus,       // 계정 파산상태 → 재기 보너스 가능

        long betEndsAt,
        int betSec,
        List<HorseView> horses,
        List<PlayerView> players,
        List<BetView> myBets,
        RaceView race,          // RACING/RESULT에서만 채움

        java.util.Map<String, Double> exactaOdds, // "i-j"(1·2등 순서) → 배당, BETTING에서만
        java.util.Map<String, Double> trioOdds,   // "i-j-k"(정렬, 1·2·3등) → 배당

        List<Integer> finishOrder, // 결과(1등부터), 없으면 빈 리스트
        long myLastNet,            // 직전 레이스 내 손익
        int buyIn,
        int horseCount,
        int playerCount,
        long totalPool,     // 이번 레이스 총 판돈(실제 배팅 합)
        long version
) {
    public record HorseView(int index, String name, String emoji, int condition, String style,
                            List<Integer> formLine, int streak, boolean isNew,
                            double oddsWin, double oddsPlace, int lane) {}

    public record PlayerView(int seat, String nick, long chips, boolean bot, boolean account, boolean host) {}

    public record BetView(String type, List<Integer> picks, long amount) {}

    public record RaceView(List<List<Integer>> timeline, List<Integer> finishOrder,
                           long raceStartAt, int tickMs, int finishDist, List<String> eventLog) {}

    public static HorseRaceStateResponse notStarted(long now) {
        return new HorseRaceStateResponse(
                "NULL_ROOM", now, "BASIC", "FIXED", 0,
                false, false, 0, null, false, 0, false,
                0, 0, List.of(), List.of(), List.of(), null,
                java.util.Map.of(), java.util.Map.of(),
                List.of(), 0, 0, 0, 0, 0, 0);
    }
}
