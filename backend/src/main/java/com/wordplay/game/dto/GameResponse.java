package com.wordplay.game.dto;

import com.wordplay.common.util.HangulUtil;
import com.wordplay.game.entity.Game;
import com.wordplay.game.entity.GameType;
import com.wordplay.similarity.SimilarityService.ReferenceScores;

import java.time.Instant;
import java.util.List;

public record GameResponse(
        String gameId,
        GameType gameType,
        String title,
        Integer wordLength,
        Integer jamoCount,
        String hintText,
        String creatorNick,
        Instant createdAt,
        Integer playCount,
        Integer solvedCount,
        Integer maxAttempts,

        Float top1Similarity,
        Float top10Similarity,
        Float top100Similarity,
        Float top1000Similarity,

        List<String> lieHints,
        Integer lieHintCount
) {
    public static GameResponse from(Game g, Integer wordGuessMaxAttempts) {
        return new GameResponse(
                g.getGameId(), g.getGameType(), g.getTitle(), g.getWordLength(),
                computeJamoCount(g),
                g.getHintText(), g.getCreatorNick(), g.getCreatedAt(),
                g.getPlayCount(), g.getSolvedCount(),
                g.getGameType() == GameType.WORDGUESS ? wordGuessMaxAttempts : null,
                null, null, null, null,
                null, null
        );
    }

    public static GameResponse fromWithSim(Game g, ReferenceScores refs) {
        return new GameResponse(
                g.getGameId(), g.getGameType(), g.getTitle(), g.getWordLength(),
                computeJamoCount(g),
                g.getHintText(), g.getCreatorNick(), g.getCreatedAt(),
                g.getPlayCount(), g.getSolvedCount(),
                null,
                refs.top1(), refs.top10(), refs.top100(), refs.top1000(),
                null, null
        );
    }

    public static GameResponse fromLieHint(Game g, List<String> hints) {
        return new GameResponse(
                g.getGameId(), g.getGameType(), g.getTitle(), g.getWordLength(),
                null,
                g.getHintText(), g.getCreatorNick(), g.getCreatedAt(),
                g.getPlayCount(), g.getSolvedCount(),
                null,
                null, null, null, null,
                hints, hints == null ? null : hints.size()
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
