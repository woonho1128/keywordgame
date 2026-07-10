package com.wordplay.othello;

import com.wordplay.othello.dto.OthelloStateResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OthelloGameTest {

    private static int cell(int r, int c) { return r * 8 + c; }

    private OthelloGame started() {
        OthelloGame g = new OthelloGame();
        g.newGame("host", "흑돌", "BLACK", 60);   // 방장 흑
        g.addBot("host", "EASY");                  // 상대 봇 백
        g.start("host");
        return g;
    }

    @Test
    void 시작하면_중앙_4개_배치되고_흑_차례() {
        OthelloGame g = started();
        assertThat(g.countForTest(1)).isEqualTo(2); // 흑 2
        assertThat(g.countForTest(2)).isEqualTo(2); // 백 2
        int[] b = g.boardForTest();
        assertThat(b[cell(3, 3)]).isEqualTo(2);
        assertThat(b[cell(3, 4)]).isEqualTo(1);
        assertThat(b[cell(4, 3)]).isEqualTo(1);
        assertThat(b[cell(4, 4)]).isEqualTo(2);
    }

    @Test
    void 흑_시작_가능수는_4개() {
        OthelloGame g = started();
        // 표준 오델로 흑 첫수 후보: (2,3)(3,2)(4,5)(5,4)
        assertThat(g.validForTest(1))
                .containsExactlyInAnyOrder(cell(2, 3), cell(3, 2), cell(4, 5), cell(5, 4));
    }

    @Test
    void 착수하면_상대돌이_뒤집힌다() {
        OthelloGame g = started();
        OthelloStateResponse s = g.place("host", cell(2, 3)); // 흑이 (2,3)에 착수 → (3,3) 뒤집힘
        int[] b = g.boardForTest();
        assertThat(b[cell(2, 3)]).isEqualTo(1);
        assertThat(b[cell(3, 3)]).isEqualTo(1); // 백→흑
        assertThat(g.countForTest(1)).isEqualTo(4); // 흑 2→4
        assertThat(s.lastMove()).isEqualTo(cell(2, 3));
    }

    @Test
    void 뒤집을_돌_없는_칸은_거부() {
        OthelloGame g = started();
        assertThat(g.validForTest(1)).doesNotContain(cell(0, 0));
    }

    @Test
    void 초고수_봇은_합법수를_두고_한판을_끝낸다() {
        OthelloGame g = new OthelloGame();
        g.newGame("host", "나", "BLACK", 60);
        g.addBot("host", "MASTER");
        g.start("host");
        // 사람(흑)도 매번 첫 유효수를 두며 진행 → 봇이 자동으로 응수해 게임이 끝나야 함
        long guard = 0;
        while (!g.me("host").status().equals("ENDED") && guard++ < 200) {
            var s = g.me("host");
            if (s.status().equals("ENDED")) break;
            if (s.myTurn() && !s.validMoves().isEmpty()) {
                g.place("host", s.validMoves().get(0));
            } else {
                // 봇 차례: 봇이 둘 시간이 되도록 데드라인을 당겨 tick 유도
                g.forceBotNowForTest();
                g.me("host");
            }
        }
        var end = g.me("host");
        assertThat(end.status()).isEqualTo("ENDED");
        assertThat(end.blackCount() + end.whiteCount()).isGreaterThan(0);
        assertThat(end.winner()).isBetween(1, 3);
    }
}
