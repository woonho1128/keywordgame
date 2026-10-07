package com.wordplay.play.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.game.entity.Game;
import com.wordplay.game.entity.GameType;
import com.wordplay.game.repository.GameRepository;
import com.wordplay.play.dto.GuessRequest;
import com.wordplay.play.dto.GuessResponse;
import com.wordplay.play.entity.GuessLog;
import com.wordplay.play.entity.PlayRecord;
import com.wordplay.play.entity.PlayStatus;
import com.wordplay.play.repository.GuessLogRepository;
import com.wordplay.play.repository.PlayRecordRepository;
import com.wordplay.similarity.SimilarityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GuessServiceTest {

    private final GameRepository gameRepository = mock(GameRepository.class);
    private final PlayRecordRepository playRecordRepository = mock(PlayRecordRepository.class);
    private final GuessLogRepository guessLogRepository = mock(GuessLogRepository.class);
    private final GuessService service = new GuessService(
            gameRepository, playRecordRepository, guessLogRepository,
            mock(SimilarityService.class), new ObjectMapper());

    private PlayRecord record;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "wordGuessMaxAttempts", 5);

        Game game = Game.builder().gameId("G1").gameType(GameType.WORDGUESS)
                .answerWord("사과").wordLength(2).playCount(1).solvedCount(0).build();
        record = PlayRecord.builder().recordId(10L).gameId("G1").sessionKey("S1").playerNick("p")
                .status(PlayStatus.IN_PROGRESS).attemptCount(0).startedAt(Instant.now()).build();

        when(gameRepository.findById("G1")).thenReturn(Optional.of(game));
        when(playRecordRepository.findForUpdate("G1", "S1")).thenReturn(Optional.of(record));
    }

    @Test
    void 추측은_행잠금_조회로_기록을_가져온다() {
        service.guess("G1", new GuessRequest("사람"), "S1");

        verify(playRecordRepository).findForUpdate("G1", "S1");
        verify(playRecordRepository, never()).findByGameIdAndSessionKey(any(), any());
    }

    @Test
    void 같은_단어_재추측은_DUPLICATE_GUESS로_거절하고_시도를_차감하지_않는다() {
        when(guessLogRepository.existsByRecordIdAndGuessWord(10L, "사람")).thenReturn(true);

        assertThatThrownBy(() -> service.guess("G1", new GuessRequest("사람"), "S1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_GUESS);

        assertThat(record.getAttemptCount()).isZero();
        verify(guessLogRepository, never()).save(any(GuessLog.class));
    }

    @Test
    void 다섯번째_오답이면_GAVE_UP과_정답_공개() {
        record.setAttemptCount(4);

        GuessResponse res = service.guess("G1", new GuessRequest("사람"), "S1");

        assertThat(res.guessOrder()).isEqualTo(5);
        assertThat(res.status()).isEqualTo("GAVE_UP");
        assertThat(res.revealedAnswer()).isEqualTo("사과");
        assertThat(record.getStatus()).isEqualTo(PlayStatus.GAVE_UP);
    }
}
