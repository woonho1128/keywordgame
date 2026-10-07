package com.wordplay.play.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordplay.game.entity.Game;
import com.wordplay.game.entity.GameType;
import com.wordplay.game.repository.GameRepository;
import com.wordplay.play.dto.GiveUpResponse;
import com.wordplay.play.entity.PlayRecord;
import com.wordplay.play.entity.PlayStatus;
import com.wordplay.play.repository.GuessLogRepository;
import com.wordplay.play.repository.PlayRecordRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlayServiceTest {

    private final GameRepository gameRepository = mock(GameRepository.class);
    private final PlayRecordRepository playRecordRepository = mock(PlayRecordRepository.class);
    private final PlayService service = new PlayService(
            gameRepository, playRecordRepository, mock(GuessLogRepository.class), new ObjectMapper());

    @Test
    void 포기하면_정답과_시도횟수_소요시간을_돌려준다() {
        Game game = Game.builder().gameId("G1").gameType(GameType.WORDGUESS)
                .answerWord("사과").wordLength(2).playCount(1).solvedCount(0).build();
        PlayRecord record = PlayRecord.builder().recordId(10L).gameId("G1").sessionKey("S1").playerNick("p")
                .status(PlayStatus.IN_PROGRESS).attemptCount(2)
                .startedAt(Instant.now().minusSeconds(75)).build();
        when(gameRepository.findById("G1")).thenReturn(Optional.of(game));
        when(playRecordRepository.findForUpdate("G1", "S1")).thenReturn(Optional.of(record));

        GiveUpResponse res = service.giveUp("G1", "S1");

        assertThat(res.answerWord()).isEqualTo("사과");
        assertThat(res.totalAttempts()).isEqualTo(2);
        assertThat(res.timeSpentSec()).isBetween(75, 77);
        assertThat(res.revealedLieIndex()).isNull();
        assertThat(record.getStatus()).isEqualTo(PlayStatus.GAVE_UP);
    }
}
