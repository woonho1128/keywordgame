package com.wordplay.play.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.util.HangulUtil.SyllableResult;
import com.wordplay.game.entity.Game;
import com.wordplay.game.repository.GameRepository;
import com.wordplay.play.dto.GiveUpResponse;
import com.wordplay.play.dto.PlayStateResponse;
import com.wordplay.play.dto.StartPlayRequest;
import com.wordplay.play.dto.StartPlayResponse;
import com.wordplay.play.entity.GuessLog;
import com.wordplay.play.entity.PlayRecord;
import com.wordplay.play.entity.PlayStatus;
import com.wordplay.play.repository.GuessLogRepository;
import com.wordplay.play.repository.PlayRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlayService {

    private final GameRepository gameRepository;
    private final PlayRecordRepository playRecordRepository;
    private final GuessLogRepository guessLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public StartPlayResponse start(String gameId, StartPlayRequest req, String sessionKey) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));

        // 같은 세션이 이미 이 게임을 진행했으면 재사용
        PlayRecord record = playRecordRepository
                .findByGameIdAndSessionKey(gameId, sessionKey)
                .orElseGet(() -> {
                    PlayRecord r = PlayRecord.builder()
                            .gameId(gameId)
                            .playerNick(req.playerNick())
                            .sessionKey(sessionKey)
                            .status(PlayStatus.IN_PROGRESS)
                            .attemptCount(0)
                            .build();
                    PlayRecord saved = playRecordRepository.save(r);

                    // 신규 플레이만 카운트
                    game.setPlayCount(game.getPlayCount() + 1);
                    gameRepository.save(game);

                    return saved;
                });

        return new StartPlayResponse(
                record.getRecordId(),
                sessionKey,
                game.getGameType(),
                game.getWordLength(),
                game.getHintText(),
                record.getAttemptCount(),
                record.getStatus().name(),
                record.getStartedAt()
        );
    }

    @Transactional
    public GiveUpResponse giveUp(String gameId, String sessionKey) {
        PlayRecord record = playRecordRepository
                .findByGameIdAndSessionKey(gameId, sessionKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        if (record.getStatus() != PlayStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.GAME_ALREADY_FINISHED);
        }

        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));

        Instant now = Instant.now();
        record.setStatus(PlayStatus.GAVE_UP);
        record.setFinishedAt(now);
        record.setTimeSpentSec((int) Duration.between(record.getStartedAt(), now).getSeconds());
        playRecordRepository.save(record);

        return new GiveUpResponse(game.getAnswerWord(), record.getAttemptCount());
    }

    /**
     * 세션이 살아있는 플레이어의 현재 플레이 상태 + 추측 기록 전체를 반환.
     * 페이지 재진입 시 닉네임 입력 없이 진행 상황을 복구하는 데 쓰인다.
     */
    @Transactional(readOnly = true)
    public PlayStateResponse getState(String gameId, String sessionKey) {
        PlayRecord record = playRecordRepository
                .findByGameIdAndSessionKey(gameId, sessionKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));

        List<PlayStateResponse.GuessItem> guesses = guessLogRepository
                .findByRecordIdOrderByGuessOrderAsc(record.getRecordId())
                .stream()
                .map(this::toGuessItem)
                .toList();

        String revealedAnswer = (record.getStatus() == PlayStatus.GAVE_UP)
                ? game.getAnswerWord() : null;

        return new PlayStateResponse(
                game.getGameType(),
                record.getStatus().name(),
                record.getAttemptCount(),
                record.getPlayerNick(),
                record.getTimeSpentSec(),
                revealedAnswer,
                guesses
        );
    }

    private PlayStateResponse.GuessItem toGuessItem(GuessLog log) {
        List<SyllableResult> letterResult = null;
        if (log.getLetterResult() != null) {
            try {
                letterResult = objectMapper.readValue(
                        log.getLetterResult(), new TypeReference<List<SyllableResult>>() {});
            } catch (JsonProcessingException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to parse letterResult");
            }
        }
        return new PlayStateResponse.GuessItem(
                log.getGuessWord(),
                log.getGuessOrder(),
                log.getIsCorrect(),
                letterResult,
                log.getSimilarity(),
                log.getRankValue()
        );
    }
}
