package com.wordplay.play.service;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.game.entity.Game;
import com.wordplay.game.repository.GameRepository;
import com.wordplay.play.dto.GiveUpResponse;
import com.wordplay.play.dto.StartPlayRequest;
import com.wordplay.play.dto.StartPlayResponse;
import com.wordplay.play.entity.PlayRecord;
import com.wordplay.play.entity.PlayStatus;
import com.wordplay.play.repository.PlayRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PlayService {

    private final GameRepository gameRepository;
    private final PlayRecordRepository playRecordRepository;

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
}
