package com.wordplay.tetris.dto;

import java.util.List;

/** 배틀 상태 응답(/me · /sync 공통). */
public record TetrisBattleState(
        String phase,          // LOBBY / PLAYING / ENDED
        String roomCode,
        String format,         // DUEL / ROYALE
        boolean isHost,
        boolean joined,
        List<PlayerView> players,     // 로비/전체 명단
        MeView me,
        List<OpponentView> opponents, // 플레이 중 나를 제외한 상대들
        int aliveCount,
        int totalPlayers,
        String winner,         // 종료 시 승자 닉(없으면 null)
        Integer myPlacement,   // 내 등수(종료/탈락 시)
        long serverNow,
        long startedMs
) {
    /** 로비/명단용 플레이어 요약. */
    public record PlayerView(String name, boolean bot, String botLevel, boolean host, boolean me, boolean alive) {}

    /** 플레이 중 상대 요약(미니보드). */
    public record OpponentView(String name, boolean bot, boolean alive, int[] heights, Integer placement) {}

    /** 나에 대한 요약. incomingGarbage는 /sync 응답으로만 전달(1회 소비). */
    public record MeView(boolean alive, Integer placement, int incomingGarbage) {}

    public static TetrisBattleState notFound(long now) {
        return new TetrisBattleState("NONE", null, null, false, false,
                List.of(), new MeView(false, null, 0), List.of(), 0, 0, null, null, now, 0);
    }
}
