package com.wordplay.play.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.util.HangulUtil.SyllableResult;
import com.wordplay.game.dto.LieHintConfig;
import com.wordplay.game.entity.Game;
import com.wordplay.game.entity.GameType;
import com.wordplay.game.repository.GameRepository;
import com.wordplay.play.dto.GiveUpResponse;
import com.wordplay.play.dto.LieHintRequest;
import com.wordplay.play.dto.LieHintResponse;
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
import java.util.Map;

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

        finalizeGaveUp(record);
        playRecordRepository.save(record);

        Integer revealedLieIndex = game.getGameType() == GameType.LIE_HINT
                ? parseLieHintConfig(game).lieIndex()
                : null;
        return new GiveUpResponse(game.getAnswerWord(), record.getAttemptCount(), revealedLieIndex);
    }

    @Transactional
    public LieHintResponse chooseLieHint(String gameId, LieHintRequest req, String sessionKey) {
        PlayRecord record = playRecordRepository
                .findByGameIdAndSessionKey(gameId, sessionKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        if (record.getStatus() != PlayStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.GAME_ALREADY_FINISHED);
        }

        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));
        if (game.getGameType() != GameType.LIE_HINT) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "This game is not Lie Hint");
        }

        GuessLog correctGuess = guessLogRepository
                .findFirstByRecordIdAndIsCorrectTrueOrderByGuessOrderAsc(record.getRecordId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "Guess the answer word first"));
        if (correctGuess.getExtraResult() != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Lie hint was already selected");
        }

        LieHintConfig config = parseLieHintConfig(game);
        boolean lieCorrect = req.selectedLieIndex().equals(config.lieIndex());
        correctGuess.setExtraResult(serialize(Map.of(
                "selectedLieIndex", req.selectedLieIndex(),
                "lieCorrect", lieCorrect
        )));
        guessLogRepository.save(correctGuess);

        if (lieCorrect) {
            finalizeSolved(game, record);
            gameRepository.save(game);
        } else {
            finalizeGaveUp(record);
        }
        playRecordRepository.save(record);

        return new LieHintResponse(
                lieCorrect,
                record.getStatus().name(),
                lieCorrect ? null : game.getAnswerWord(),
                lieCorrect ? null : config.lieIndex(),
                record.getTimeSpentSec()
        );
    }

    @Transactional(readOnly = true)
    public PlayStateResponse getState(String gameId, String sessionKey) {
        PlayRecord record = playRecordRepository
                .findByGameIdAndSessionKey(gameId, sessionKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));

        List<GuessLog> logs = guessLogRepository.findByRecordIdOrderByGuessOrderAsc(record.getRecordId());
        List<PlayStateResponse.GuessItem> guesses = logs.stream()
                .map(this::toGuessItem)
                .toList();

        String revealedAnswer = record.getStatus() == PlayStatus.GAVE_UP
                ? game.getAnswerWord()
                : null;
        Integer revealedLieIndex = record.getStatus() == PlayStatus.GAVE_UP && game.getGameType() == GameType.LIE_HINT
                ? parseLieHintConfig(game).lieIndex()
                : null;
        Boolean liePhase = game.getGameType() == GameType.LIE_HINT
                ? logs.stream().anyMatch(log -> Boolean.TRUE.equals(log.getIsCorrect()) && log.getExtraResult() == null)
                        && record.getStatus() == PlayStatus.IN_PROGRESS
                : null;

        return new PlayStateResponse(
                game.getGameType(),
                record.getStatus().name(),
                record.getAttemptCount(),
                record.getPlayerNick(),
                record.getTimeSpentSec(),
                revealedAnswer,
                revealedLieIndex,
                liePhase,
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
                log.getRankValue(),
                log.getExtraResult()
        );
    }

    private void finalizeSolved(Game game, PlayRecord record) {
        Instant now = Instant.now();
        record.setStatus(PlayStatus.SOLVED);
        record.setFinishedAt(now);
        record.setTimeSpentSec((int) Duration.between(record.getStartedAt(), now).getSeconds());
        game.setSolvedCount(game.getSolvedCount() + 1);
    }

    private void finalizeGaveUp(PlayRecord record) {
        Instant now = Instant.now();
        record.setStatus(PlayStatus.GAVE_UP);
        record.setFinishedAt(now);
        record.setTimeSpentSec((int) Duration.between(record.getStartedAt(), now).getSeconds());
    }

    private LieHintConfig parseLieHintConfig(Game game) {
        try {
            return objectMapper.readValue(game.getGameConfig(), LieHintConfig.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to parse Lie Hint config");
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to serialize extra result");
        }
    }
}
