package com.wordplay.leaderboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordplay.common.util.HangulUtil;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** WordGuess 리더보드 — 남의 입력 이력은 게임을 끝낸 사람에게만 내려준다. */
class LeaderboardServiceTest {

    private final LeaderboardRepository leaderboardRepository = mock(LeaderboardRepository.class);
    private final GameRepository gameRepository = mock(GameRepository.class);
    private final PlayRecordRepository playRecordRepository = mock(PlayRecordRepository.class);
    private final GuessLogRepository guessLogRepository = mock(GuessLogRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LeaderboardService service = new LeaderboardService(
            leaderboardRepository, gameRepository, playRecordRepository, guessLogRepository, objectMapper);

    @BeforeEach
    void setUp() throws Exception {
        when(gameRepository.findById("G1")).thenReturn(Optional.of(Game.builder()
                .gameId("G1").gameType(GameType.WORDGUESS).answerWord("사과").wordLength(2)
                .playCount(2).solvedCount(1).build()));

        PlayRecord solver = PlayRecord.builder().recordId(1L).gameId("G1").playerNick("철수")
                .status(PlayStatus.SOLVED).attemptCount(2).timeSpentSec(30).build();
        when(leaderboardRepository.findRankings(eq("G1"), any())).thenReturn(List.of(solver));
        when(leaderboardRepository.countSolvers("G1")).thenReturn(1L);

        when(guessLogRepository.findByRecordIdInOrderByRecordIdAscGuessOrderAsc(anyCollection())).thenReturn(List.of(
                log(1L, 1, "석수", false),
                log(1L, 2, "사과", true)));
    }

    private GuessLog log(Long recordId, int order, String word, boolean correct) throws Exception {
        String letters = objectMapper.writeValueAsString(HangulUtil.compareWords("사과", word));
        return GuessLog.builder().recordId(recordId).guessOrder(order).guessWord(word)
                .letterResult(letters).isCorrect(correct).build();
    }

    private void viewer(String sessionKey, PlayStatus status) {
        when(playRecordRepository.findByGameIdAndSessionKey("G1", sessionKey)).thenReturn(Optional.of(
                PlayRecord.builder().recordId(9L).gameId("G1").sessionKey(sessionKey).status(status).build()));
    }

    @Test
    void 게임을_끝낸_사람에게는_정답자의_입력_이력을_순서대로_보여준다() {
        viewer("DONE", PlayStatus.GAVE_UP);

        LeaderboardResponse res = service.getLeaderboard("G1", 50, "DONE");

        assertThat(res.detailVisible()).isTrue();
        assertThat(res.gameType()).isEqualTo(GameType.WORDGUESS);
        List<LeaderboardEntry.GuessRow> rows = res.rankings().get(0).guesses();
        assertThat(rows).extracting(LeaderboardEntry.GuessRow::guessWord).containsExactly("석수", "사과");
        assertThat(rows).extracting(LeaderboardEntry.GuessRow::isCorrect).containsExactly(false, true);
        assertThat(rows.get(0).letterResult().get(0).marks()).extracting(HangulUtil.JamoMark::mark)
                .containsExactly("H", "S", "H");   // 석 → ㅅ✓ ㅓ✗ ㄱ✓
    }

    @Test
    void 아직_푸는_중인_사람에게는_이력을_내려주지_않는다() {
        viewer("PLAYING", PlayStatus.IN_PROGRESS);

        LeaderboardResponse res = service.getLeaderboard("G1", 50, "PLAYING");

        assertThat(res.detailVisible()).isFalse();
        assertThat(res.rankings().get(0).guesses()).isNull();
        verify(guessLogRepository, never()).findByRecordIdInOrderByRecordIdAscGuessOrderAsc(anyCollection());
    }

    @Test
    void 플레이_기록이_없는_사람에게도_이력을_내려주지_않는다() {
        LeaderboardResponse res = service.getLeaderboard("G1", 50, null);

        assertThat(res.detailVisible()).isFalse();
        assertThat(res.rankings().get(0).guesses()).isNull();
        assertThat(res.rankings().get(0).rank()).isEqualTo(1);
    }
}
