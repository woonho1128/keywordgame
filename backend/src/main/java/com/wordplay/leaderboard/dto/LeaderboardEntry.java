package com.wordplay.leaderboard.dto;

import com.wordplay.common.util.HangulUtil.SyllableResult;

import java.time.Instant;
import java.util.List;

public record LeaderboardEntry(
        Integer rank,               // 성공자 순위 (실패자는 null)
        String playerNick,
        String status,              // "SOLVED" | "GAVE_UP"
        Integer attemptCount,
        Integer timeSpentSec,
        Instant finishedAt,
        // 아래 상세는 게임을 끝낸 사람이 볼 때만 채워짐(스포일러 방지). 그 외 null.
        Integer selectedLieIndex,   // Lie Hint — 거짓 힌트로 고른 0-based 번호, 선택 안 했으면 null
        List<GuessRow> guesses      // WordGuess — 입력한 단어와 자모 판정, 시도 순서대로
) {
    public record GuessRow(String guessWord, List<SyllableResult> letterResult, Boolean isCorrect) {}
}
