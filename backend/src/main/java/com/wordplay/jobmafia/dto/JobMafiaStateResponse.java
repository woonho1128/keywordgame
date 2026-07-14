package com.wordplay.jobmafia.dto;

import java.util.List;

/**
 * 직업 마피아 폴링 응답.
 *
 * status(=phase): NOT_STARTED / LOBBY / NIGHT / MORNING / DISCUSS / VOTE / EXECUTE / ENDED
 * actionKind: MAFIA_KILL / POLICE_CHECK / DOCTOR_SAVE / CITIZEN_WATCH / VOTE / NONE
 *
 * ⚠️ 정신병자(PSYCHO)에게는 myRole/actionKind가 '가짜 직업'(경찰/의사)으로 내려간다.
 *    본인은 진짜인 줄 알지만 능력은 효과가 없다. 정체는 ENDED 시 공개.
 */
public record JobMafiaStateResponse(
        String status,
        long round,
        long phaseEndsAt,
        long serverNow,
        boolean isHost,
        boolean joined,
        int seat,
        String nick,
        String myRole,       // 표시용(정신병자는 가짜 직업), 시작 전 null
        String myTeam,        // CITIZEN/MAFIA/NEUTRAL(표시용)
        boolean alive,
        List<PlayerView> players,
        String actionKind,
        boolean copMafiaInvestigate,   // 경찰마피아: 이번 밤 조사모드 여부(살해모드=false)
        List<Integer> selectable,
        int myTarget,
        List<Integer> fellowMafia,
        List<String> copLog,           // 경찰(및 가짜경찰 정신병자)의 조사 기록
        List<VoteView> mafiaPickTally, // 마피아 실시간 지목 현황
        String nightMessage,
        int nightDeadSeat,
        int executedSeat,
        List<VoteView> voteTally,
        int accusedSeat,               // 재판대에 오른 좌석(1-based, 없으면 -1)
        int killVotes,                 // 사형 표 수(FINAL_VOTE/EXECUTE)
        int spareVotes,                // 생존 표 수
        int myFinalVote,               // 내 사형투표: -1 미투표 / 0 생존 / 1 사형
        String winner,                 // ENDED: CITIZEN/MAFIA/NEUTRAL
        int aliveCount,
        int playerCount,
        List<String> myHistory,        // 나만 보는 개인 기록(내 능력·투표)
        List<String> history           // 전체 공개 진행 이력(오래된 순)
) {
    public record PlayerView(int seat, String nick, boolean alive, String role) {}
    public record VoteView(int targetSeat, int count) {}

    public static JobMafiaStateResponse notStarted(long now) {
        return new JobMafiaStateResponse(
                "NOT_STARTED", 0, 0, now, false, false, 0, null, null, null, false,
                List.of(), "NONE", false, List.of(), -1, List.of(), List.of(), List.of(),
                null, -1, -1, List.of(), -1, 0, 0, -1, null, 0, 0, List.of(), List.of());
    }
}
