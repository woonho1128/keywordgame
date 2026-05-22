package com.wordplay.play.dto;

import com.wordplay.common.util.HangulUtil.SyllableResult;

import java.util.List;

public record GuessResponse(
        String guessWord,
        Integer guessOrder,
        Boolean isCorrect,

        List<SyllableResult> letterResult,

        Boolean inDictionary,
        Float similarity,
        Float similarityScore,
        Integer rank,

        Boolean liePhase,

        String status,
        String revealedAnswer,
        Integer timeSpentSec
) {
    public static GuessResponse wordGuess(
            String guessWord, int order, boolean correct,
            List<SyllableResult> letterResult,
            String status, String revealedAnswer, Integer timeSpentSec) {
        return new GuessResponse(
                guessWord, order, correct, letterResult,
                null, null, null, null,
                null,
                status, revealedAnswer, timeSpentSec
        );
    }

    public static GuessResponse wordSim(
            String guessWord, int order, boolean correct,
            boolean inDict, Float similarity, Integer rank,
            String status, String revealedAnswer, Integer timeSpentSec) {
        Float score = similarity == null ? null : similarity * 100f;
        return new GuessResponse(
                guessWord, order, correct, null,
                inDict, similarity, score, rank,
                null,
                status, revealedAnswer, timeSpentSec
        );
    }

    public static GuessResponse lieHint(
            String guessWord, int order, boolean correct,
            boolean liePhase, String status, String revealedAnswer, Integer timeSpentSec) {
        return new GuessResponse(
                guessWord, order, correct, null,
                null, null, null, null,
                liePhase,
                status, revealedAnswer, timeSpentSec
        );
    }
}
