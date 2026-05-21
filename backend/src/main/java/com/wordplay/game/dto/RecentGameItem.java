package com.wordplay.game.dto;

import com.wordplay.game.entity.Game;
import com.wordplay.game.entity.GameType;

import java.time.Instant;

public record RecentGameItem(
        String gameId,
        GameType gameType,
        String hintText,
        String creatorNick,
        Integer wordLength,
        Integer playCount,
        Integer solvedCount,
        Instant createdAt
) {
    public static RecentGameItem from(Game g) {
        return new RecentGameItem(
                g.getGameId(), g.getGameType(), g.getHintText(), g.getCreatorNick(),
                g.getWordLength(), g.getPlayCount(), g.getSolvedCount(), g.getCreatedAt()
        );
    }
}
