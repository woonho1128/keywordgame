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

    @Transactional
    public GuessResponse guess(String gameId, GuessRequest req, String sessionKey) {
        // 1. 세션 → PlayRecord
        PlayRecord record = playRecordRepository
                .findByGameIdAndSessionKey(gameId, sessionKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        if (record.getStatus() != PlayStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.GAME_ALREADY_FINISHED);
        }

        // 2. 게임 정보
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));

        // 3. 추측 정규화 + 검증
        String guessWord = TextNormalizer.normalize(req.guessWord());
        if (guessWord == null || guessWord.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "추측 단어를 입력해주세요");
        }

        if (game.getGameType() == GameType.WORDGUESS) {
            return guessWordGuess(game, record, guessWord);
        } else {
            return guessWordSim(game, record, guessWord);
        }
    }

    // ------------------------------------------------------------------
    // WordGuess — HangulUtil로 자모 비교
    // ------------------------------------------------------------------
    private GuessResponse guessWordGuess(Game game, PlayRecord record, String guess) {
        if (!HangulUtil.isAllHangulSyllables(guess)) {
            throw new BusinessException(ErrorCode.INVALID_HANGUL);
        }
        // 자모 수 일치만 확인 (음절 수는 자유 — 꼬들 표준)
        int answerJamos = HangulUtil.countJamos(game.getAnswerWord());
        int guessJamos = HangulUtil.countJamos(guess);
        if (answerJamos != guessJamos) {
            throw new BusinessException(ErrorCode.INVALID_WORD_LENGTH);
        }

        boolean correct = guess.equals(game.getAnswerWord());
        List<SyllableResult> result = HangulUtil.compareWords(game.getAnswerWord(), guess);
        int order = record.getAttemptCount() + 1;

        // 로그 저장
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
        if (correct) {
            finalizeSolved(game, record);
        }
        playRecordRepository.save(record);

        return GuessResponse.wordGuess(guess, order, correct, result);
    }

    // ------------------------------------------------------------------
    // WordSim — 메모리 임베딩 사전 기반 유사도/순위 계산
    // ------------------------------------------------------------------
    private GuessResponse guessWordSim(Game game, PlayRecord record, String guess) {
        if (!similarityService.isLoaded()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "WordSim 사전이 로드되지 않았습니다");
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
        // inDict=false → similarity/rank null → 프론트에서 "사전에 없는 단어" 메시지

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

        return GuessResponse.wordSim(guess, order, correct, inDict, similarity, rank);
    }

    private void finalizeSolved(Game game, PlayRecord record) {
        Instant now = Instant.now();
        record.setStatus(PlayStatus.SOLVED);
        record.setFinishedAt(now);
        record.setTimeSpentSec((int) Duration.between(record.getStartedAt(), now).getSeconds());

        game.setSolvedCount(game.getSolvedCount() + 1);
        gameRepository.save(game);
    }
}
