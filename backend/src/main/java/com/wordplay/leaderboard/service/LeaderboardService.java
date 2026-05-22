package com.wordplay.leaderboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.game.entity.Game;
import com.wordplay.game.entity.GameType;
import com.wordplay.game.repository.GameRepository;
import com.wordplay.leaderboard.dto.LeaderboardEntry;
import com.wordplay.leaderboard.dto.LeaderboardResponse;
import com.wordplay.leaderboard.repository.LeaderboardRepository;
import com.wordplay.play.entity.GuessLog;
import com.wordplay.play.entity.PlayRecord;
import com.wordplay.play.entity.PlayStatus;
import com.wordplay.play.repository.GuessLogRepository;
import com.wordplay.play.repository.PlayRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private final LeaderboardRepository leaderboardRepository;
    private final GameRepository gameRepository;
    private final PlayRecordRepository playRecordRepository;
    private final GuessLogRepository guessLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public LeaderboardResponse getLeaderboard(String gameId, int limit, String viewerSessionKey) {
        int clamped = Math.min(Math.max(limit, 1), 100);

        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));
        boolean isLieHint = game.getGameType() == GameType.LIE_HINT;

        List<PlayRecord> solved = leaderboardRepository.findRankings(gameId, PageRequest.of(0, clamped));
        long total = leaderboardRepository.countSolvers(gameId);

        // 실패자는 Lie Hint에서만 노출
        List<PlayRecord> failed = isLieHint
                ? leaderboardRepository.findFailures(gameId, PageRequest.of(0, clamped))
                : List.of();

        // 상세(거짓 힌트 선택값) 공개 여부: Lie Hint이고, 보는 사람이 이 게임을 끝냈을 때만
        boolean detailVisible = isLieHint && viewerSessionKey != null
                && playRecordRepository.findByGameIdAndSessionKey(gameId, viewerSessionKey)
                        .map(r -> r.getStatus() != PlayStatus.IN_PROGRESS)
                        .orElse(false);

        // 거짓 힌트 선택값 — 상세 공개 가능할 때만 채운다 (응답 자체에 안 실으면 스포일러 불가)
        Map<Long, Integer> lieSelections = detailVisible
                ? loadLieSelections(Stream.concat(solved.stream(), failed.stream())
                        .map(PlayRecord::getRecordId).toList())
                : Map.of();

        List<LeaderboardEntry> rankings = buildEntries(solved, true, lieSelections);
        List<LeaderboardEntry> failures = buildEntries(failed, false, lieSelections);

        return new LeaderboardResponse((int) total, rankings, failures, detailVisible);
    }

    /** 각 record의 정답 추측 로그에서 거짓 힌트 선택값(selectedLieIndex)을 뽑아낸다. */
    private Map<Long, Integer> loadLieSelections(List<Long> recordIds) {
        if (recordIds.isEmpty()) return Map.of();
        Map<Long, Integer> result = new HashMap<>();
        for (GuessLog log : guessLogRepository.findByRecordIdInAndIsCorrectTrue(recordIds)) {
            if (log.getExtraResult() == null) continue;
            try {
                JsonNode node = objectMapper.readTree(log.getExtraResult());
                JsonNode idx = node.get("selectedLieIndex");
                if (idx != null && idx.isInt()) {
                    result.put(log.getRecordId(), idx.asInt());
                }
            } catch (Exception ignored) {
                // 파싱 실패 시 해당 항목은 상세 없이 표시
            }
        }
        return result;
    }

    private List<LeaderboardEntry> buildEntries(List<PlayRecord> records, boolean ranked,
                                                Map<Long, Integer> lieSelections) {
        List<LeaderboardEntry> entries = new ArrayList<>(records.size());
        int rank = 1;
        for (PlayRecord r : records) {
            entries.add(new LeaderboardEntry(
                    ranked ? rank++ : null,
                    r.getPlayerNick(),
                    r.getStatus().name(),
                    r.getAttemptCount(),
                    r.getTimeSpentSec(),
                    r.getFinishedAt(),
                    lieSelections.get(r.getRecordId())
            ));
        }
        return entries;
    }
}
