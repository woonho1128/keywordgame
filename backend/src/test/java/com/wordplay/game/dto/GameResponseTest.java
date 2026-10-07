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
    void WordGuess는_글자수와_자모수를_함께_공개한다() {
        // 추측도 정답과 글자 수가 같아야 하므로(GuessService) 글자 수를 화면에 보여준다
        GameResponse r = GameResponse.from(game(GameType.WORDGUESS), 5);

        assertThat(r.wordLength()).isEqualTo(2);
        assertThat(r.jamoCount()).isEqualTo(5);
        assertThat(r.maxAttempts()).isEqualTo(5);
    }

    @Test
    void 다른_모드는_자모수와_시도제한이_없다() {
        GameResponse r = GameResponse.from(game(GameType.WORDSIM), 5);

        assertThat(r.wordLength()).isEqualTo(2);
        assertThat(r.jamoCount()).isNull();
        assertThat(r.maxAttempts()).isNull();
    }
}
