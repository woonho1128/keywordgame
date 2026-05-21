package com.wordplay.game.dto;

import com.wordplay.common.util.HangulUtil;
import com.wordplay.game.entity.Game;
import com.wordplay.game.entity.GameType;

import java.time.Instant;

public record GameResponse(
        String gameId,
        GameType gameType,
        Integer wordLength,
        Integer jamoCount,       // 정답의 총 자모 수 (꼬들 표준 — 음절 구조 노출 최소화)
        String hintText,
        String creatorNick,
        Instant createdAt,
        Integer playCount,
        Integer solvedCount
) {
    public static GameResponse from(Game g) {
        return new GameResponse(
                g.getGameId(), g.getGameType(), g.getWordLength(),
                computeJamoCount(g),
                g.getHintText(), g.getCreatorNick(), g.getCreatedAt(),
                g.getPlayCount(), g.getSolvedCount()
        );
    }

    private static Integer computeJamoCount(Game g) {
        if (g.getGameType() != GameType.WORDGUESS) return null;
        String answer = g.getAnswerWord();
        if (answer == null) return null;
        int count = 0;
        for (int i = 0; i < answer.length(); i++) {
            char c = answer.charAt(i);
            if (HangulUtil.isHangulSyllable(c)) {
                count += HangulUtil.decompose(c).size();
            }
        }
        return count;
    }
}
