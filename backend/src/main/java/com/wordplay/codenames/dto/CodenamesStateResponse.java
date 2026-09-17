package com.wordplay.codenames.dto;

import java.util.List;

/**
 * 코드네임 폴링 응답.
 * status(=phase): NOT_STARTED / LOBBY / CLUE(힌트 대기) / GUESS(추측) / ENDED
 *
 * board 각 칸의 color: 공개됐거나 내가 스파이마스터면 실제 색(RED/BLUE/NEUTRAL/ASSASSIN),
 * 아니면 null(요원에겐 미공개 칸이 안 보임).
 */
public record CodenamesStateResponse(
        String status,
        long serverNow,
        boolean isHost,
        boolean joined,
        int seat,
        String nick,
        String myTeam,          // RED/BLUE/null
        boolean amSpymaster,
        List<PlayerView> players,
        List<Cell> board,
        String currentTeam,
        String startTeam,
        String clueWord,
        int clueNumber,
        int guessesLeft,
        int redRemaining,
        int blueRemaining,
        boolean amActiveSpymaster,
        boolean amActiveOperative,
        String winner,          // RED/BLUE
        String winReason,
        int playerCount
) {
    public record PlayerView(int seat, String nick, String team, boolean spymaster) {}
    public record Cell(int index, String word, boolean revealed, String color) {}

    public static CodenamesStateResponse notStarted(long now) {
        return new CodenamesStateResponse(
                "NOT_STARTED", now, false, false, 0, null, null, false,
                List.of(), List.of(), null, null, null, 0, 0, 0, 0,
                false, false, null, null, 0);
    }
}
