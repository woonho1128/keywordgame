package com.wordplay.leaderboard.dto;

import com.wordplay.game.entity.GameType;

import java.util.List;

public record LeaderboardResponse(
        Integer totalPlayers,            // 정답자 수 (기존 의미 유지)
        List<LeaderboardEntry> rankings, // 성공자, 시도/시간 순 정렬
        List<LeaderboardEntry> failures, // 실패자 (Lie Hint만 채워짐, 그 외 빈 리스트)
        Boolean detailVisible,           // 보는 사람이 상세(Lie Hint 선택값, WordGuess 입력 이력)를 볼 자격이 있는지
        GameType gameType                // 화면 안내 문구를 모드별로 고르기 위함
) {}
