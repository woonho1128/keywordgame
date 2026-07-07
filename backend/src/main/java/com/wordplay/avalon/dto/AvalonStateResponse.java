package com.wordplay.avalon.dto;

import java.util.List;

/**
 * 아발론 폴링 응답.
 * status(=phase): NOT_STARTED / LOBBY / REVEAL / TEAM_BUILD / TEAM_VOTE / QUEST / ASSASSIN / ENDED
 */
public record AvalonStateResponse(
        String status,
        long phaseEndsAt,
        long serverNow,
        boolean isHost,
        boolean joined,
        int seat,
        String nick,
        String myRole,        // 시작 전 null
        String myTeam,        // GOOD/EVIL
        List<String> knowledge, // 이 플레이어가 아는 정보(멀린/퍼시발/악 동료 등)
        List<PlayerView> players,
        int leaderSeat,
        boolean amLeader,
        int questNumber,      // 1-based 현재 원정
        List<Integer> teamSizes,     // 5개 원정 인원
        List<Integer> failsRequired, // 5개 원정 실패 필요 수
        List<String> questResults,   // 완료된 원정 결과 SUCCESS/FAIL
        int successCount,
        int failCount,
        int rejectCount,
        List<Integer> proposedTeam,  // 제안된 원정대(1-based)
        int teamSizeNeeded,
        boolean amOnTeam,
        boolean amReady,
        int readyCount,
        String myVote,        // APPROVE/REJECT/null
        int votesCast,
        List<VoteView> lastVote,   // 직전 투표 공개(찬반 내역)
        String lastVoteResult,     // APPROVED/REJECTED/null
        String myCard,        // SUCCESS/FAIL/null
        int questSubmitted,
        int lastQuestFails,   // 직전 원정 실패 카드 수, 없으면 -1
        boolean amAssassin,
        int assassinTargetSeat,
        int merlinSeat,       // ENDED 시 공개, 아니면 -1
        String winner,        // GOOD/EVIL
        String winReason,
        int playerCount
) {
    public record PlayerView(int seat, String nick, String role) {}
    public record VoteView(int seat, boolean approve) {}

    public static AvalonStateResponse notStarted(long now) {
        return new AvalonStateResponse(
                "NOT_STARTED", 0, now, false, false, 0, null, null, null, List.of(),
                List.of(), 0, false, 0, List.of(), List.of(), List.of(), 0, 0, 0,
                List.of(), 0, false, false, 0, null, 0, List.of(), null, null, 0, -1,
                false, -1, -1, null, null, 0);
    }
}
