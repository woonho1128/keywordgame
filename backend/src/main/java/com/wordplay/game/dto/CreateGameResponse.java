package com.wordplay.game.dto;

import com.wordplay.game.entity.GameType;

public record CreateGameResponse(
        String gameId,
        String shareUrl,
        GameType gameType,
        Integer wordLength
) {}
