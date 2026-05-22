package com.wordplay.game.dto;

import com.wordplay.game.entity.GameType;

public record CreateGameResponse(
        String gameId,
        String shareUrl,
        String title,
        String creatorNick,
        GameType gameType,
        Integer wordLength
) {}
