package com.wordplay.play.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.util.HangulUtil;
import com.wordplay.common.util.HangulUtil.SyllableResult;
import com.wordplay.common.util.TextNormalizer;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuessService {

    private final GameRepository gameRepository;
    private final PlayRecordRepository playRecordRepository;
    private final GuessLogRepository guessLogRepository;
    private final SimilarityService similarityService;
    private final ObjectMapper objectMapper;

    @Value("${app.game.wordguess-max-attempts:5}")
    private int wordGuessMaxAttempts;

    @Transactional
    public GuessResponse guess(String gameId, GuessRequest req, String sessionKey) {
        PlayRecord record = playRecordRepository
                .findByGameIdAndSessionKey(gameId, sessionKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        if (record.getStatus() != PlayStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.GAME_ALREADY_FINISHED);
        }

        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));

        String guessWord = TextNormalizer.normalize(req.guessWord());
        if (guessWord == null || guessWord.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Enter a guess word");
        }

        if (game.getGameType() == GameType.WORDGUESS) {
            return guessWordGuess(game, record, guessWord);
        }
        if (game.getGameType() == GameType.WORDSIM) {
            return guessWordSim(game, record, guessWord);
        }
        return guessLieHint(game, record, guessWord);
    }

    private GuessResponse guessWordGuess(Game game, PlayRecord record, String guess) {
        if (!HangulUtil.isAllHangulSyllables(guess)) {
            throw new BusinessException(ErrorCode.INVALID_HANGUL);
        }
        int answerJamos = HangulUtil.countJamos(game.getAnswerWord());
        int guessJamos = HangulUtil.countJamos(guess);
        if (answerJamos != guessJamos) {
            throw new BusinessException(ErrorCode.INVALID_WORD_LENGTH);
        }

        boolean correct = guess.equals(game.getAnswerWord());
        List<SyllableResult> result = HangulUtil.compareWords(game.getAnswerWord(), guess);
        int order = record.getAttemptCount() + 1;

        String letterResultJson;
        try {
            letterResultJson = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to serialize letterResult");
        }

        guessLogRepository.save(GuessLog.builder()
                .recordId(record.getRecordId())
                .guessWord(guess)
                .guessOrder(order)
                .letterResult(letterResultJson)
                .isCorrect(correct)
                .build());

        record.setAttemptCount(order);

        String revealedAnswer = null;
        if (correct) {
            finalizeSolved(game, record);
        } else if (order >= wordGuessMaxAttempts) {
            finalizeGaveUp(record);
            revealedAnswer = game.getAnswerWord();
        }
        playRecordRepository.save(record);

        Integer timeSpent = (record.getStatus() != PlayStatus.IN_PROGRESS) ? record.getTimeSpentSec() : null;
        return GuessResponse.wordGuess(
                guess, order, correct, result,
                record.getStatus().name(), revealedAnswer, timeSpent
        );
    }

    private GuessResponse guessWordSim(Game game, PlayRecord record, String guess) {
        if (!similarityService.isAvailable()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "WordSim is unavailable because no dictionary or OpenAI key is configured");
        }

        boolean correct = guess.equals(game.getAnswerWord());
        int order = record.getAttemptCount() + 1;

        boolean inDict = similarityService.contains(guess);
        Float similarity = null;
        Integer rank = null;

        if (correct) {
            similarity = 1.0f;
            rank = 0;
        } else if (inDict) {
            similarity = similarityService.cosine(game.getAnswerWord(), guess);
            rank = similarityService.rank(game.getAnswerWord(), guess);
        }

        guessLogRepository.save(GuessLog.builder()
                .recordId(record.getRecordId())
                .guessWord(guess)
                .guessOrder(order)
                .similarity(similarity)
                .rankValue(rank)
                .isCorrect(correct)
                .build());

        record.setAttemptCount(order);
        if (correct) {
            finalizeSolved(game, record);
        }
        playRecordRepository.save(record);

        Integer timeSpent = (record.getStatus() != PlayStatus.IN_PROGRESS) ? record.getTimeSpentSec() : null;
        return GuessResponse.wordSim(
                guess, order, correct, inDict, similarity, rank,
                record.getStatus().name(), null, timeSpent
        );
    }

    private GuessResponse guessLieHint(Game game, PlayRecord record, String guess) {
        if (guessLogRepository.findFirstByRecordIdAndIsCorrectTrueOrderByGuessOrderAsc(record.getRecordId()).isPresent()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Choose the lie hint to finish this game");
        }

        boolean correct = guess.equals(game.getAnswerWord());
        int order = record.getAttemptCount() + 1;

        guessLogRepository.save(GuessLog.builder()
                .recordId(record.getRecordId())
                .guessWord(guess)
                .guessOrder(order)
                .isCorrect(correct)
                .build());

        record.setAttemptCount(order);
        playRecordRepository.save(record);

        return GuessResponse.lieHint(
                guess, order, correct, correct,
                record.getStatus().name(), null, null
        );
    }

    private void finalizeSolved(Game game, PlayRecord record) {
        Instant now = Instant.now();
        record.setStatus(PlayStatus.SOLVED);
        record.setFinishedAt(now);
        record.setTimeSpentSec((int) Duration.between(record.getStartedAt(), now).getSeconds());

        game.setSolvedCount(game.getSolvedCount() + 1);
        gameRepository.save(game);
    }

    private void finalizeGaveUp(PlayRecord record) {
        Instant now = Instant.now();
        record.setStatus(PlayStatus.GAVE_UP);
        record.setFinishedAt(now);
        record.setTimeSpentSec((int) Duration.between(record.getStartedAt(), now).getSeconds());
    }
}
