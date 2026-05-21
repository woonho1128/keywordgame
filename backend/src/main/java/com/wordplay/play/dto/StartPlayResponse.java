package com.wordplay.play.dto;

import com.wordplay.game.entity.GameType;

import java.time.Instant;

public record StartPlayResponse(
        Long recordId,
        String sessionKey,
        GameType gameType,
        Integer wordLength,
        String hintText,
        Integer attemptCount,
        String status,
        Instant startedAt
) {}
