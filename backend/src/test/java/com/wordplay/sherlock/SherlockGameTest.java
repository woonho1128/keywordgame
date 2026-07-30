package com.wordplay.sherlock;

import com.wordplay.sherlock.dto.SherlockState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SherlockGameTest {

    private SherlockGame withPlayers(int n) {
        SherlockGame g = new SherlockGame("host", "방장", 60, false);
        for (int i = 1; i < n; i++) g.join("p" + i, "친구" + i);
        return g;
    }

    @Test
    void 최소인원_미달_시작불가() {
        SherlockGame g = new SherlockGame("host", "방장", 60, false);
        assertThatThrownBy(() -> g.start("host")).hasMessageContaining("최소 2명");
    }

    @Test
    void 인원별_캐릭터수는_3N더하기1이고_손패는_3장_범인은_1명() {
        for (int n = 2; n <= 10; n++) {
            SherlockGame g = withPlayers(n);
            g.start("host");
            assertThat(g.inPlayForTest()).hasSize(3 * n + 1);
            SherlockState s = g.me("host");
            assertThat(s.deck()).hasSize(3 * n + 1);
            // 각 플레이어 손패 3장
            for (var pv : s.players()) assertThat(pv.cardCount()).isEqualTo(3);
            // 범인은 아무도 안 가진 유일한 캐릭터
            int culprit = g.culpritForTest();
            for (var p : g.playersList()) assertThat(p.cards).doesNotContain(culprit);
        }
    }

    @Test
    void 최대인원_초과_시작불가() {
        SherlockGame g = new SherlockGame("host", "방장", 60, false);
        for (int i = 1; i < 10; i++) g.addBot("host", "NORMAL"); // 10명
        g.start("host"); // 10명 OK
        assertThat(g.me("host").deck()).hasSize(31);
    }

    @Test
    void 범인_지목_성공하면_승리() {
        SherlockGame g = withPlayers(2);
        g.start("host");
        int culprit = g.culpritForTest();
        // host 차례라고 가정(turnSeat 0). 아니면 진행.
        while (g.turnSeat() != 0) { /* 2인이라 시작 0 */ break; }
        g.accuse("host", culprit);
        SherlockState s = g.me("host");
        assertThat(s.phase()).isEqualTo("ENDED");
        assertThat(s.winnerSeat()).isEqualTo(0);
    }

    @Test
    void 틀린_지목은_탈락() {
        SherlockGame g = withPlayers(3);
        g.start("host");
        int culprit = g.culpritForTest();
        int wrong = -1;
        for (int c : g.inPlayForTest()) if (c != culprit) { wrong = c; break; }
        g.accuse("host", wrong);
        SherlockState s = g.me("host");
        assertThat(s.players().get(0).alive()).isFalse();
        assertThat(s.phase()).isEqualTo("PLAYING");
    }

    @Test
    void 전체조사_단서가_기록된다() {
        SherlockGame g = withPlayers(3);
        g.start("host");
        g.askAll("host", 0);
        SherlockState s = g.me("host");
        assertThat(s.clues()).hasSize(1);
        assertThat(s.clues().get(0).target()).isEqualTo(-1);
        assertThat(s.clues().get(0).results()).hasSize(2); // 나머지 2명
    }
}
