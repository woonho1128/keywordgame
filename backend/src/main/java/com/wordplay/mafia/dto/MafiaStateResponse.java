package com.wordplay.mafia.dto;

import java.util.List;

/**
 * 마피아 폴링 응답. 클라이언트는 이걸 1초마다 받아 화면을 그린다.
 *
 * status(=phase):
 *   NOT_STARTED — 방 없음
 *   LOBBY       — 대기방(참가/시작 대기)
 *   NIGHT       — 밤(마피아/경찰/의사 행동)
 *   MORNING     — 아침 발표(밤 결과)
 *   DISCUSS     — 낮 토론
 *   VOTE        — 투표
 *   EXECUTE     — 처형 발표
 *   ENDED       — 종료(승리팀·정체 공개)
 *
 * actionKind: 이 순간 내가 해야 할 행동
 *   MAFIA_KILL / POLICE_CHECK / DOCTOR_SAVE / VOTE / NONE
 */
public record MafiaStateResponse(
        String status,
        long round,
        long phaseEndsAt,   // epoch millis, 타이머 없으면 0
        long serverNow,     // 서버 현재 시각(클라 남은시간 계산용)
        boolean isHost,
        boolean joined,
        int seat,           // 1-based, 미참가 0
        String nick,
        String myRole,      // MAFIA/POLICE/DOCTOR/CITIZEN, 시작 전이면 null
        String myTeam,      // MAFIA/CITIZEN, 시작 전이면 null
        boolean alive,
        List<PlayerView> players,
        String actionKind,
        List<Integer> selectable, // 이번에 지목/투표 가능한 좌석들
        int myTarget,             // 내 현재 선택, 없으면 -1
        List<Integer> fellowMafia,// 마피아만: 동료 좌석
        List<String> copLog,      // 경찰만: 조사 기록
        String nightMessage,      // 아침/이후: 밤 결과 문구
        int nightDeadSeat,        // 밤 사망 좌석, 없으면 -1
        int executedSeat,         // 처형 좌석, 없으면 -1
        List<VoteView> voteTally, // 투표 집계
        String winner,            // ENDED에서만 MAFIA/CITIZEN
        int aliveCount,
        int totalMafia,
        int playerCount,
        List<ChatView> mafiaChat  // 마피아에게만: 밤 채팅
) {
    public record PlayerView(int seat, String nick, boolean alive, String role) {}
    public record VoteView(int targetSeat, int count) {}
    public record ChatView(long round, String nick, String text) {}

    public static MafiaStateResponse notStarted(long now) {
        return new MafiaStateResponse(
                "NOT_STARTED", 0, 0, now, false, false, 0, null, null, null, false,
                List.of(), "NONE", List.of(), -1, List.of(), List.of(),
                null, -1, -1, List.of(), null, 0, 0, 0, List.of());
    }
}
