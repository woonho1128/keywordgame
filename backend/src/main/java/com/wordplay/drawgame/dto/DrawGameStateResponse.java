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
        List<AlbumView> albums, // REVEAL에서만
        int playerCount,
        long version
) {
    public record PlayerView(int seat, String nick, boolean submitted) {}
    public record StepView(String type, String content, String authorNick) {}
    public record AlbumView(String ownerNick, List<StepView> steps) {}

    public static DrawGameStateResponse notStarted(long now) {
        return new DrawGameStateResponse(
                "NOT_STARTED", "GARTIC", "FREE", now, false, false, 0, null, List.of(),
                0, 0, null, null, null, false, 0, 0, List.of(), 0, 0);
    }
}
