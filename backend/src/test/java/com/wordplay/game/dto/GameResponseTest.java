package com.wordplay.game.dto;

import com.wordplay.game.entity.Game;
import com.wordplay.game.entity.GameType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GameResponseTest {

    private static Game game(GameType type) {
        return Game.builder().gameId("G1").gameType(type).answerWord("사과").wordLength(2)
                .playCount(0).solvedCount(0).build();
    }

    @Test
    void WordGuess는_음절수를_숨기고_자모수만_공개한다() {
        GameResponse r = GameResponse.from(game(GameType.WORDGUESS), 5);

        assertThat(r.wordLength()).isNull();
        assertThat(r.jamoCount()).isEqualTo(5);
        assertThat(r.maxAttempts()).isEqualTo(5);
    }

    @Test
    void 다른_모드는_음절수를_그대로_내려준다() {
        assertThat(GameResponse.from(game(GameType.WORDSIM), 5).wordLength()).isEqualTo(2);
    }
}
