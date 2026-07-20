package com.wordplay.yut;

import com.wordplay.yut.dto.YutState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YutGameTest {

    @Test
    void 최소인원_미달_시작불가() {
        YutGame g = new YutGame("host", "방장", false, true);
        assertThatThrownBy(() -> g.start("host")).hasMessageContaining("최소 2명");
    }

    @Test
    void 팀전은_4명_필요() {
        YutGame g = new YutGame("host", "방장", true, true);
        g.addBot("host", "NORMAL");
        assertThatThrownBy(() -> g.start("host")).hasMessageContaining("팀전은 4명");
    }

    @Test
    void 시작하면_말4개_대기_첫차례_던지기() {
        YutGame g = new YutGame("host", "방장", false, true);
        g.join("p2", "친구");
        g.start("host");
        assertThat(g.phase()).isEqualTo(YutGame.Phase.PLAYING);
        assertThat(g.turnSeat()).isEqualTo(0);
        YutState st = g.me("host");
        assertThat(st.throwsOwed()).isEqualTo(1);
        for (YutGame.P p : g.playersList()) { assertThat(p.done).isZero(); for (String t : p.tok) assertThat(t).isEqualTo("wait"); }
        // 던지기 전엔 이동 불가
        assertThatThrownBy(() -> g.move("host", 1, 0, "o0")).hasMessageContaining("사용할 수 없");
        // 남의 차례엔 던지기 불가
        assertThatThrownBy(() -> g.throwYut("p2", 60)).hasMessageContaining("차례");
    }

    @Test
    void 던지면_pending에_값이_쌓이고_이동하면_말이_전진한다() {
        for (int attempt = 0; attempt < 80; attempt++) {
            YutGame g = new YutGame("host", "방장", false, true);
            g.join("p2", "친구");
            g.start("host");
            while (g.me("host").throwsOwed() > 0) g.throwYut("host", 60); // 윷/모 연속 포함
            YutState st = g.me("host");
            YutState.Move mv = st.moves().stream().filter(m -> m.value() > 0).findFirst().orElse(null);
            if (mv == null) continue; // 첫 던지기가 백도뿐이면 다시
            String dest = mv.dests().get(0).cell();
            g.move("host", mv.value(), mv.tokenIndex(), dest);
            assertThat(g.playersList().get(0).tok[mv.tokenIndex()]).isEqualTo(dest);
            return;
        }
        throw new AssertionError("양수 이동 케이스를 얻지 못했습니다");
    }

    @Test
    void 봇들끼리_게임이_끝까지_진행되어_승리팀이_나온다() {
        YutGame g = new YutGame("host", "방장", false, true);
        g.addBot("host", "NORMAL");
        g.start("host"); // host(사람) + 봇1 = 2인
        int guard = 0;
        while (g.phase() != YutGame.Phase.ENDED && guard++ < 20000) {
            g.speedUpBotsForTest();
            if (g.turnSeat() == 0 && g.phase() == YutGame.Phase.PLAYING) {
                // host(사람) 차례를 봇처럼 자동 진행: 던질 게 있으면 던지고, 이동 후보 있으면 첫 후보
                YutState st = g.me("host");
                if (st.throwsOwed() > 0) g.throwYut("host", 60);
                else if (!st.moves().isEmpty()) {
                    YutState.Move m = st.moves().get(0);
                    g.move("host", m.value(), m.tokenIndex(), m.dests().get(0).cell());
                } else g.me("host");
            } else {
                g.me("host"); // 봇 차례 진행
            }
        }
        assertThat(g.phase()).isEqualTo(YutGame.Phase.ENDED);
        assertThat(g.winnerTeam()).isGreaterThanOrEqualTo(0);
    }
}
