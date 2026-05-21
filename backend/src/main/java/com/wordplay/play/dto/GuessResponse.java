package com.wordplay.play.dto;

import com.wordplay.common.util.HangulUtil.SyllableResult;

import java.util.List;

/**
 * 추측 응답 (WordGuess + WordSim 통합).
 *
 * 게임이 이번 추측으로 종료된 경우(SOLVED / GAVE_UP) status, revealedAnswer,
 * timeSpentSec 가 함께 반환되어 프론트에서 결과 화면을 바로 그릴 수 있다.
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
        Integer rank,

        // 게임 상태 (모든 모드)
        String status,            // "IN_PROGRESS" | "SOLVED" | "GAVE_UP"
        String revealedAnswer,    // GAVE_UP로 전환된 경우만 (실패 시 정답 공개)
        Integer timeSpentSec      // 게임 종료 시점에만
) {
    public static GuessResponse wordGuess(
            String guessWord, int order, boolean correct,
            List<SyllableResult> letterResult,
            String status, String revealedAnswer, Integer timeSpentSec) {
        return new GuessResponse(
                guessWord, order, correct, letterResult,
                null, null, null, null,
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
                status, revealedAnswer, timeSpentSec
        );
    }
}
