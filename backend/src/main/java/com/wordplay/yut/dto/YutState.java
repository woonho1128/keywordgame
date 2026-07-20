package com.wordplay.yut.dto;

import java.util.List;

/** 윷놀이 상태 응답. */
public record YutState(
        String phase,               // LOBBY / PLAYING / ENDED
        boolean teamMode,
        boolean backDo,
        boolean isHost,
        boolean joined,
        List<PlayerView> players,
        int turnSeat,
        String turnName,
        boolean myTurn,
        int mySeat,
        int myTeam,
        int throwsOwed,             // 남은 던지기 횟수
        List<ThrowResult> pending,  // 아직 사용 안 한 던지기 결과
        List<Move> moves,           // 내가 지금 둘 수 있는 이동(값·말·도착 후보)
        String lastAction,
        int winnerTeam,             // -1 없음
        String winnerLabel,
        boolean abilitiesOn,
        String myAbility,           // 내 능력 타입(없으면 null) — 남의 것은 비공개
        String myAbilityName,
        String myAbilityDesc,
        boolean myAbilityUsed,
        List<String> log,           // 최근 이력(오래된→최신)
        long deadline,
        long serverNow
) {
    /** tokens: 말 4개의 셀 id("wait"/"done"/셀). hasAbility: 아직 안 쓴 능력 보유(종류는 비공개). */
    public record PlayerView(int seat, String name, boolean bot, boolean host, boolean me,
                             int team, int color, List<String> tokens, int doneCount, boolean left, boolean hasAbility) {}

    /** 던지기 결과. value: 도1 개2 걸3 윷4 모5 백도-1. */
    public record ThrowResult(String name, int value, boolean extra) {}

    /** 이동 후보: value 결과로 tokenIndex 말(그룹)을 dests 중 하나로. */
    public record Move(int value, String name, int tokenIndex, List<Dest> dests) {}
    public record Dest(String cell, String label, boolean caught, boolean finish) {}

    public static YutState notFound(long now) {
        return new YutState("NONE", false, true, false, false, List.of(), -1, null, false, -1, -1,
                0, List.of(), List.of(), null, -1, null, false, null, null, null, false, List.of(), 0, now);
    }
}
