package com.wordplay.leaderboard.service;

import com.wordplay.leaderboard.dto.LeaderboardEntry;
import com.wordplay.leaderboard.dto.LeaderboardResponse;
import com.wordplay.leaderboard.repository.LeaderboardRepository;
import com.wordplay.play.entity.PlayRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private final LeaderboardRepository leaderboardRepository;

    @Transactional(readOnly = true)
    public LeaderboardResponse getLeaderboard(String gameId, int limit) {
        int clamped = Math.min(Math.max(limit, 1), 100);
        List<PlayRecord> records = leaderboardRepository.findRankings(gameId, PageRequest.of(0, clamped));
        long total = leaderboardRepository.countSolvers(gameId);

        List<LeaderboardEntry> entries = new ArrayList<>(records.size());
        int rank = 1;
        for (PlayRecord r : records) {
            entries.add(new LeaderboardEntry(
                    rank++,
                    r.getPlayerNick(),
                    r.getAttemptCount(),
                    r.getTimeSpentSec(),
                    r.getFinishedAt()
            ));
        }
        return new LeaderboardResponse((int) total, entries);
    }
}
