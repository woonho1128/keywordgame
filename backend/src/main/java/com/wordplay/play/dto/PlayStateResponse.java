package com.wordplay.play.dto;

import com.wordplay.common.util.HangulUtil.SyllableResult;
import com.wordplay.game.entity.GameType;

import java.util.List;

/**
 * 진행 중/완료된 플레이 복구용 응답.
 * 세션이 살아있는 플레이어가 페이지에 재진입하면 닉네임 입력 없이
 * 기존 추측 기록과 게임 상태를 그대로 복원할 수 있도록 한다.
 */
public record PlayStateResponse(
        GameType gameType,
        String status,            // "IN_PROGRESS" | "SOLVED" | "GAVE_UP"
        Integer attemptCount,
        String playerNick,
        Integer timeSpentSec,     // 게임 종료된 경우만
        String revealedAnswer,    // GAVE_UP인 경우만 (정답 공개)
        List<GuessItem> guesses
) {
    public record GuessItem(
            String guessWord,
            Integer guessOrder,
            Boolean isCorrect,
            // WordGuess only
            List<SyllableResult> letterResult,
            // WordSim only
            Float similarity,
            Integer rank
    ) {}
}
