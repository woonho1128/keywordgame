package com.wordplay.spicy;

import com.wordplay.spicy.dto.SpicyState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다음 차례 노출.
 *
 * <p>점수판이 점수순으로 정렬돼 있어 점수가 바뀔 때마다 줄이 뒤바뀌고, 그래서 순서를
 * 읽을 수 없다는 건의가 있었다. 화면을 좌석순으로 고정하면서 "다음 차례"도 서버가
 * 알려준다 — 프론트에서 계산하면 나간 사람 처리가 서버와 어긋난다.
 */
class SpicyNextSeatTest {

    private SpicyGame withPlayers(int n) {
        SpicyGame g = new SpicyGame("host", "방장", 8, null);
        for (int i = 1; i < n; i++) g.join("p" + i, "친구" + i);
        return g;
    }

    @Test
    void 다음_좌석을_알려준다() {
        SpicyGame g = withPlayers(4);
        g.start("host");
        SpicyState st = g.me("host");
        assertThat(st.nextSeat()).isEqualTo((st.turnSeat() + 1) % 4);
    }

    @Test
    void 나간_사람은_건너뛴다() {
        SpicyGame g = withPlayers(4);
        g.start("host");
        int turn = g.turnSeat();
        g.playersList().get((turn + 1) % 4).left = true;

        assertThat(g.me("host").nextSeat()).isEqualTo((turn + 2) % 4);
    }

    @Test
    void 시작_전에는_다음_좌석이_없다() {
        SpicyGame g = withPlayers(4);
        assertThat(g.me("host").nextSeat()).isEqualTo(-1);
    }
}
