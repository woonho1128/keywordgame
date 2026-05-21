package com.wordplay.play.dto;

import com.wordplay.common.util.HangulUtil.SyllableResult;

import java.util.List;

/**
 * 두 게임 모드의 추측 응답을 하나로 합친 형태.
 * 클라이언트는 gameType에 따라 필요한 필드만 사용.
 */
public record GuessResponse(
        String guessWord,
        Integer guessOrder,
        Boolean isCorrect,

        // WordGuess only
        List<SyllableResult> letterResult,

        // WordSim only
        Boolean inDictionary,
        Float similarity,
        Float similarityScore,
        Integer rank
) {
    public static GuessResponse wordGuess(String guessWord, int order, boolean correct, List<SyllableResult> letterResult) {
        return new GuessResponse(guessWord, order, correct, letterResult, null, null, null, null);
    }

    public static GuessResponse wordSim(String guessWord, int order, boolean correct,
                                        boolean inDict, Float similarity, Integer rank) {
        Float score = similarity == null ? null : similarity * 100f;
        return new GuessResponse(guessWord, order, correct, null, inDict, similarity, score, rank);
    }
}
