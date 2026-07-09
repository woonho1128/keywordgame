package com.wordplay.drawgame.dto;

import java.util.List;

/** 그림 게임 상태 응답. status: NOT_STARTED / LOBBY / PLAYING / REVEAL */
public record DrawGameStateResponse(
        String status,
        String mode,          // GARTIC / CATCHMIND
        String topicMode,     // FREE / RANDOM
        long serverNow,
        boolean isHost,
        boolean joined,
        int seat,
        String nick,
        List<PlayerView> players,
        int round,
        int totalRounds,
        String taskType,      // WRITE_INITIAL / DRAW / WRITE / null
        String promptText,    // DRAW일 때 그릴 문장
        String promptImage,   // WRITE일 때 설명할 그림(data URL)
        boolean mySubmitted,
        int submittedCount,
        long deadline,        // 이번 라운드 마감(ms), 0이면 없음
        List<AlbumView> albums, // REVEAL에서만(갈틱폰)
        int playerCount,
        long version,
        // ---- 캐치마인드 전용 ----
        int drawerSeat,       // 이번 라운드 그리는 사람(1-based), 없으면 0
        boolean amDrawer,     // 내가 그리는 사람인가
        String myWord,        // 제시어(그리는 사람·정답자·공개 시에만)
        String snapshot,      // 현재 그림(data URL, 맞히는 사람에게)
        List<GuessView> guesses, // 맞히기 채팅 피드
        List<ScoreView> scores,  // 점수판
        String lastAnswer,    // 직전 라운드 정답(표시용)
        boolean iGuessedCorrect
) {
    public record PlayerView(int seat, String nick, boolean submitted) {}
    public record StepView(String type, String content, String authorNick) {}
    public record AlbumView(String ownerNick, List<StepView> steps) {}
    public record GuessView(String nick, String text, boolean correct) {}
    public record ScoreView(int seat, String nick, int score) {}

    public static DrawGameStateResponse notStarted(long now) {
        return new DrawGameStateResponse(
                "NOT_STARTED", "GARTIC", "FREE", now, false, false, 0, null, List.of(),
                0, 0, null, null, null, false, 0, 0, List.of(), 0, 0,
                0, false, null, null, List.of(), List.of(), null, false);
    }
}
