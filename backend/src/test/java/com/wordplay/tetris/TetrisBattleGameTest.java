package com.wordplay.tetris;

import com.wordplay.tetris.dto.TetrisBattleState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TetrisBattleGameTest {

    private static int[] flat() { return new int[10]; }

    @Test
    void 듀얼_상대가_죽으면_승자가_결정되고_등수가_매겨진다() {
        TetrisBattleGame g = new TetrisBattleGame("A", "알파", "DUEL");
        g.join("B", "베타");
        g.start("A");
        assertThat(g.me("A").phase()).isEqualTo("PLAYING");

        TetrisBattleState after = g.sync("B", 0, flat(), false); // B 탈락 보고
        assertThat(after.phase()).isEqualTo("ENDED");
        assertThat(after.winner()).isEqualTo("알파");
        assertThat(after.myPlacement()).isEqualTo(2);           // B는 2등
        assertThat(g.me("A").myPlacement()).isEqualTo(1);       // A는 1등(우승)
    }

    @Test
    void 공격은_상대에게_라우팅되어_한번만_전달된다() {
        TetrisBattleGame g = new TetrisBattleGame("A", "알파", "DUEL");
        g.join("B", "베타");
        g.start("A");

        g.sync("A", 4, flat(), true);                    // A가 4줄 공격 → B에게
        TetrisBattleState b1 = g.sync("B", 0, flat(), true);
        assertThat(b1.me().incomingGarbage()).isEqualTo(4);   // 1회 전달
        TetrisBattleState b2 = g.sync("B", 0, flat(), true);
        assertThat(b2.me().incomingGarbage()).isEqualTo(0);   // 소비 후 0
    }

    @Test
    void 배틀로얄_탈락순서대로_등수가_역순으로_부여된다() {
        TetrisBattleGame g = new TetrisBattleGame("A", "알파", "ROYALE");
        g.join("B", "베타");
        g.join("C", "감마");
        g.start("A");

        g.sync("C", 0, flat(), false); // C 먼저 탈락 → 3등
        assertThat(g.me("C").myPlacement()).isEqualTo(3);
        assertThat(g.me("A").phase()).isEqualTo("PLAYING"); // 아직 진행

        g.sync("B", 0, flat(), false); // B 탈락 → 2등, A 우승(1등)
        TetrisBattleState end = g.me("A");
        assertThat(end.phase()).isEqualTo("ENDED");
        assertThat(end.winner()).isEqualTo("알파");
        assertThat(end.myPlacement()).isEqualTo(1);
        assertThat(g.me("B").myPlacement()).isEqualTo(2);
    }

    @Test
    void 봇을_추가해_혼자_시작할_수_있다() {
        TetrisBattleGame g = new TetrisBattleGame("A", "알파", "DUEL");
        g.addBot("A", "HARD");
        TetrisBattleState s = g.start("A");
        assertThat(s.phase()).isEqualTo("PLAYING");
        assertThat(s.totalPlayers()).isEqualTo(2);
        assertThat(s.opponents()).hasSize(1);
        assertThat(s.opponents().get(0).bot()).isTrue();
    }
}
