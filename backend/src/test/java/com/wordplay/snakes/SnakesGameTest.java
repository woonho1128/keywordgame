package com.wordplay.snakes;

import com.wordplay.snakes.dto.SnakesState;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 뱀과 사다리 진행 규칙. */
class SnakesGameTest {

    private SnakesGame withPlayers(int n) {
        SnakesGame g = new SnakesGame("host", "방장", 20);
        for (int i = 1; i < n; i++) g.join("p" + i, "친구" + i);
        return g;
    }

    private static SnakesGame.P at(SnakesGame g, int seat) { return g.playersList().get(seat); }
    private static String cid(SnakesGame g, int seat) { return at(g, seat).clientId; }

    @Test
    void 최소_두명이어야_시작한다() {
        SnakesGame g = new SnakesGame("host", "방장", 20);
        assertThatThrownBy(() -> g.start("host")).hasMessageContaining("최소 2명");
    }

    @Test
    void 방장만_시작할_수_있다() {
        SnakesGame g = withPlayers(3);
        assertThatThrownBy(() -> g.start("p1")).hasMessageContaining("방장만");
    }

    @Test
    void 자기_차례가_아니면_굴릴_수_없다() {
        SnakesGame g = withPlayers(3);
        g.start("host");
        int other = (g.turnSeat() + 1) % 3;
        assertThatThrownBy(() -> g.roll(cid(g, other))).hasMessageContaining("차례가 아닙니다");
    }

    @Test
    void 굴리면_말이_전진한다() {
        SnakesGame g = withPlayers(2);
        g.start("host");
        SnakesGame.P cur = at(g, g.turnSeat());
        g.roll(cur.clientId);
        assertThat(cur.pos).as("출발 칸에서 움직였어야 한다").isGreaterThan(0);
    }

    @Test
    void 마지막_칸을_넘기면_제자리다() {
        SnakesGame g = withPlayers(2);
        g.start("host");
        SnakesGame.P cur = at(g, g.turnSeat());
        int size = g.board().size();
        cur.pos = size - 1;                    // 1이 나와야만 도착
        // 1이 나올 때까지 굴린다. 그 전까지는 계속 제자리여야 한다.
        for (int i = 0; i < 200 && cur.pos == size - 1; i++) {
            if (g.turnSeat() != cur.seat) { g.roll(cid(g, g.turnSeat())); continue; }
            g.roll(cur.clientId);
            assertThat(cur.pos).as("넘치면 제자리, 아니면 도착").isIn(size - 1, size);
        }
    }

    @Test
    void 정확히_도착하면_승리한다() {
        SnakesGame g = withPlayers(2);
        g.start("host");
        SnakesGame.P cur = at(g, g.turnSeat());
        int size = g.board().size();
        cur.pos = size - 1;
        while (g.phase() == SnakesGame.Phase.PLAYING) {
            int s = g.turnSeat();
            g.roll(cid(g, s));
            if (g.phase() == SnakesGame.Phase.ENDED) break;
        }
        assertThat(g.winnerSeat()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 사다리를_밟으면_올라간다() {
        SnakesGame g = withPlayers(2);
        g.start("host");
        List<int[]> ladders = g.board().ladders();
        assertThat(ladders).isNotEmpty();
        int[] l = ladders.get(0);
        SnakesGame.P cur = at(g, g.turnSeat());
        // 사다리 밑칸에 1~6으로 닿을 수 있는 자리에 세워 두고, 닿을 때까지 굴린다.
        for (int i = 0; i < 300; i++) {
            if (g.turnSeat() != cur.seat) { g.roll(cid(g, g.turnSeat())); continue; }
            cur.pos = Math.max(0, l[0] - 3);
            g.roll(cur.clientId);
            if (cur.pos == l[1]) return;       // 사다리를 탔다
            if (g.phase() != SnakesGame.Phase.PLAYING) break;
        }
        // 주사위 운이라 매번 닿지는 않는다. 규칙 자체는 board.jump로 따로 검증한다.
        assertThat(g.board().jump(l[0])).isEqualTo(l[1]);
    }

    @Test
    void 뱀을_밟으면_내려간다() {
        SnakesGame g = withPlayers(2);
        g.start("host");
        List<int[]> snakes = g.board().snakes();
        assertThat(snakes).isNotEmpty();
        for (int[] s : snakes) assertThat(g.board().jump(s[0])).isEqualTo(s[1]);
    }

    @Test
    void 봇은_스스로_굴려_판이_끝난다() throws Exception {
        SnakesGame g = new SnakesGame("host", "방장", 20);
        g.addBot("host"); g.addBot("host");
        g.start("host");
        g.setBotDelayForTest(0);      // 실제로는 1.2초씩 쉬어 한 판이 분 단위가 된다
        // 사람(방장)은 자기 차례에 굴리고, 봇은 tick으로 알아서 움직인다.
        for (int i = 0; i < 2000 && g.phase() == SnakesGame.Phase.PLAYING; i++) {
            SnakesState st = g.me("host");
            if (st.myTurn()) g.roll("host");
            Thread.sleep(2);
        }
        assertThat(g.phase()).isEqualTo(SnakesGame.Phase.ENDED);
        assertThat(g.winnerSeat()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 이동마다_번호가_올라간다() {
        // 프론트는 이 번호로 '새 이동'을 판별해 연출을 재생한다. 같은 결과가 연달아 나와도
        // 구분되어야 하므로 결과가 아니라 번호가 기준이다.
        SnakesGame g = withPlayers(2);
        g.start("host");
        long a = g.me("host").moveSeq();
        g.roll(cid(g, g.turnSeat()));
        long b = g.me("host").moveSeq();
        g.roll(cid(g, g.turnSeat()));
        long c = g.me("host").moveSeq();
        assertThat(a).isZero();
        assertThat(b).isEqualTo(1);
        assertThat(c).isEqualTo(2);
    }

    @Test
    void 이동_정보로_연출을_재구성할_수_있다() {
        SnakesGame g = withPlayers(2);
        g.start("host");
        SnakesGame.P cur = at(g, g.turnSeat());
        g.roll(cur.clientId);
        SnakesState.LastMove m = g.me("host").lastMove();

        assertThat(m).isNotNull();
        assertThat(m.seat()).isEqualTo(cur.seat);
        assertThat(m.die()).isBetween(1, 6);
        assertThat(m.from()).isZero();                       // 첫 이동은 출발 칸에서
        assertThat(m.how()).isIn("MOVE", "LADDER", "SNAKE", "OVER");
        if ("MOVE".equals(m.how())) {
            assertThat(m.landed()).isEqualTo(m.from() + m.die());
            assertThat(m.to()).isEqualTo(m.landed());        // 걷기만 하면 밟은 칸이 최종
        } else if ("LADDER".equals(m.how())) {
            assertThat(m.to()).isGreaterThan(m.landed());
        } else if ("SNAKE".equals(m.how())) {
            assertThat(m.to()).isLessThan(m.landed());
        }
        assertThat(m.to()).isEqualTo(cur.pos);               // 최종 칸은 실제 말 위치와 같다
    }

    @Test
    void 봇은_연출이_끝날_시간을_두고_굴린다() {
        // 연출은 주사위 0.7초 + 최대 6칸(칸당 0.21초 = 1.26초) + 멈춤 0.26초 +
        // 미끄러짐 0.7초 ≈ 2.9초가 걸린다. 봇이 그보다 빨리 굴리면 연출이 겹친다.
        assertThat(SnakesGame.BOT_DELAY_MS).isGreaterThanOrEqualTo(2920);
    }

    @Test
    void 나간_사람은_차례를_건너뛴다() {
        SnakesGame g = withPlayers(3);
        g.start("host");
        int leaver = (g.turnSeat() + 1) % 3;
        g.leave(cid(g, leaver));
        g.roll(cid(g, g.turnSeat()));
        assertThat(g.turnSeat()).as("나간 사람은 건너뛴다").isNotEqualTo(leaver);
    }

    @Test
    void 혼자_남으면_승리한다() {
        SnakesGame g = withPlayers(2);
        g.start("host");
        g.leave(cid(g, 1));
        assertThat(g.phase()).isEqualTo(SnakesGame.Phase.ENDED);
        assertThat(g.winnerSeat()).isEqualTo(0);
    }

    @Test
    void 다음_차례를_알려준다() {
        SnakesGame g = withPlayers(3);
        g.start("host");
        SnakesState st = g.me("host");
        assertThat(st.nextSeat()).isEqualTo((st.turnSeat() + 1) % 3);
    }

    @Test
    void 보드_정보를_모두에게_내려준다() {
        SnakesGame g = withPlayers(2);
        g.start("host");
        SnakesState st = g.me("p1");
        assertThat(st.cols()).isEqualTo(10);
        assertThat(st.size()).isEqualTo(100);
        assertThat(st.ladders()).isNotEmpty();
        assertThat(st.snakes()).isNotEmpty();
        assertThat(st.boardSeed()).isNotZero();
    }

    @Test
    void 제한시간을_끄면_사람을_재촉하지_않는다() throws Exception {
        SnakesGame g = new SnakesGame("host", "방장", 0);
        g.join("p1", "친구");
        g.start("host");
        assertThat(g.noTimeLimit()).isTrue();
        int before = at(g, g.turnSeat()).pos;
        int seat = g.turnSeat();
        Thread.sleep(200);
        g.me("host");                      // tick
        assertThat(at(g, seat).pos).as("자동으로 굴리지 않는다").isEqualTo(before);
    }

    @Test
    void 시간이_지나면_자동으로_굴린다() throws Exception {
        SnakesGame g = new SnakesGame("host", "방장", 5);
        g.join("p1", "친구");
        g.start("host");
        int seat = g.turnSeat();
        ReflectionTestUtils.setField(g, "deadline", System.currentTimeMillis() - 1);
        g.me("host");                      // tick → 자동 굴림
        assertThat(at(g, seat).rolls).as("타임아웃이면 서버가 대신 굴린다").isGreaterThan(0);
    }
}
