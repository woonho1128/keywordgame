package com.wordplay.play.dto;

import com.wordplay.common.util.HangulUtil.SyllableResult;
import com.wordplay.game.entity.GameType;

import java.util.List;

public record PlayStateResponse(
        GameType gameType,
        String status,
        Integer attemptCount,
        String playerNick,
        Integer timeSpentSec,
        String revealedAnswer,
        Integer revealedLieIndex,
        Boolean liePhase,
        List<GuessItem> guesses
) {
    public record GuessItem(
            String guessWord,
            Integer guessOrder,
            Boolean isCorrect,
            List<SyllableResult> letterResult,
            Float similarity,
            Integer rank,
            String extraResult
    ) {}
}
