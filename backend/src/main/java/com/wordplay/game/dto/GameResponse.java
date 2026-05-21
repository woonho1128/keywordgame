package com.wordplay.game.dto;

import com.wordplay.common.util.HangulUtil;
import com.wordplay.game.entity.Game;
import com.wordplay.game.entity.GameType;
import com.wordplay.similarity.SimilarityService.ReferenceScores;

import java.time.Instant;

public record GameResponse(
        String gameId,
        GameType gameType,
        Integer wordLength,
        Integer jamoCount,            // WordGuess: 정답 자모 수
        String hintText,
        String creatorNick,
        Instant createdAt,
        Integer playCount,
        Integer solvedCount,
        // WordSim 참고 점수 (정답과의 유사도 분포)
        Float top1Similarity,
        Float top10Similarity,
        Float top100Similarity,
        Float top1000Similarity
) {
    public static GameResponse from(Game g) {
        return new GameResponse(
                g.getGameId(), g.getGameType(), g.getWordLength(),
                computeJamoCount(g),
                g.getHintText(), g.getCreatorNick(), g.getCreatedAt(),
                g.getPlayCount(), g.getSolvedCount(),
                null, null, null, null
        );
    }

    public static GameResponse fromWithSim(Game g, ReferenceScores refs) {
        return new GameResponse(
                g.getGameId(), g.getGameType(), g.getWordLength(),
                computeJamoCount(g),
                g.getHintText(), g.getCreatorNick(), g.getCreatedAt(),
                g.getPlayCount(), g.getSolvedCount(),
                refs.top1(), refs.top10(), refs.top100(), refs.top1000()
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
