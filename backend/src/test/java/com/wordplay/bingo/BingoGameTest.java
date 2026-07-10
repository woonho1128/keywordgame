package com.wordplay.bingo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BingoGameTest {

    @Test
    void 크기에_따라_범위_결정() {
        BingoGame g = new BingoGame();
        g.newGame("host", "p0", 3, 3);
        assertThat(g.me("host").range()).isEqualTo(15);
        assertThat(g.me("host").size()).isEqualTo(3);
        g.newGame("host", "p0", 5, 3);
        assertThat(g.me("host").range()).isEqualTo(50);
    }

    @Test
    void 판_숫자_개수_틀리면_거부() {
        BingoGame g = new BingoGame();
        g.newGame("host", "p0", 3, 3);
        assertThatThrownBy(() -> g.setBoard("host", List.of(1, 2, 3)))
                .hasMessageContaining("9개");
    }

    @Test
    void 중복_숫자_거부() {
        BingoGame g = new BingoGame();
        g.newGame("host", "p0", 3, 3);
        assertThatThrownBy(() -> g.setBoard("host", List.of(1, 1, 2, 3, 4, 5, 6, 7, 8)))
                .hasMessageContaining("중복");
    }

    @Test
    void 한줄_완성하면_목표1_승리() {
        BingoGame g = new BingoGame();
        g.newGame("host", "p0", 3, 1); // target 1줄
        g.join("c1", "p1");
        g.setBoard("host", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9));      // 첫 줄 = 1,2,3
        g.setBoard("c1", List.of(10, 11, 12, 13, 14, 15, 7, 8, 9));  // 겹치지 않는 줄
        g.start("host");
        g.debugDraw(1);
        g.debugDraw(2);
        assertThat(g.me("host").status()).isEqualTo("PLAYING");
        g.debugDraw(3); // host 첫 줄 완성 → 승리
        assertThat(g.me("host").status()).isEqualTo("ENDED");
        assertThat(g.me("host").winnerSeat()).isEqualTo(1);
        assertThat(g.linesForTest(0)).isEqualTo(1);
    }

    @Test
    void TURN모드_번갈아_지목() {
        BingoGame g = new BingoGame();
        g.newGame("host", "p0", 3, 1, "TURN");
        g.join("c1", "p1");
        g.setBoard("host", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9));
        g.setBoard("c1", List.of(10, 11, 12, 13, 14, 15, 7, 8, 9));
        g.start("host");
        assertThat(g.me("host").mode()).isEqualTo("TURN");
        int turn = g.me("host").currentTurnSeat(); // 1 또는 2
        assertThat(turn).isIn(1, 2);

        String cur = turn == 1 ? "host" : "c1";
        String other = turn == 1 ? "c1" : "host";
        // 내 차례 아닌 사람은 지목 불가
        assertThatThrownBy(() -> g.callNumber(other, 1)).hasMessageContaining("차례");
        // 이미 나온/범위 밖 검증
        assertThat(g.me(cur).myTurn()).isTrue();
        g.callNumber(cur, 1);
        assertThat(g.me("host").drawn()).contains(1);
        // 차례가 상대로 넘어감
        assertThat(g.me(other).myTurn()).isTrue();
        assertThatThrownBy(() -> g.callNumber(cur, 2)).hasMessageContaining("차례");
    }

    @Test
    void 최소인원_미달() {
        BingoGame g = new BingoGame();
        g.newGame("host", "p0", 3, 3);
        g.setBoard("host", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9));
        assertThatThrownBy(() -> g.start("host")).hasMessageContaining("최소 2명");
    }
}
