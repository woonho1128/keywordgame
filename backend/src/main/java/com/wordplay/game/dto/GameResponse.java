package com.wordplay.game.dto;

import com.wordplay.game.entity.Game;
import com.wordplay.game.entity.GameType;

import java.time.Instant;

public record GameResponse(
        String gameId,
        GameType gameType,
        Integer wordLength,
        String hintText,
        String creatorNick,
        Instant createdAt,
        Integer playCount,
        Integer solvedCount
) {
    public static GameResponse from(Game g) {
        return new GameResponse(
                g.getGameId(), g.getGameType(), g.getWordLength(),
                g.getHintText(), g.getCreatorNick(), g.getCreatedAt(),
                g.getPlayCount(), g.getSolvedCount()
        );
    }
}
