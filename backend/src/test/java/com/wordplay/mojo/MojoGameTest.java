package com.wordplay.mojo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MojoGameTest {

    @Test
    void 색상별_최고숫자만_합산() {
        // 🔵0-1, 🟢2-4, 🟡5-7, 🟠8-10, 🔴11-12
        assertThat(MojoGame.scoreCards(List.of(1, 0, 4, 2, 7, 5))).isEqualTo(1 + 4 + 7); // 파랑1 초록4 노랑7
        assertThat(MojoGame.scoreCards(List.of(12, 11, 12))).isEqualTo(12);              // 빨강 최고 1개
        assertThat(MojoGame.scoreCards(List.of())).isEqualTo(0);
        assertThat(MojoGame.scoreCards(List.of(0, 3, 6, 9, 12))).isEqualTo(0 + 3 + 6 + 9 + 12);
    }

    @Test
    void 덱_구성_78장() {
        int total = 0;
        for (int n = 0; n <= 12; n++) total += MojoGame.countFor(n);
        assertThat(total).isEqualTo(78);
    }

    @Test
    void 최소인원_미달_시작불가() {
        MojoGame g = new MojoGame("host", "방장", false);
        assertThatThrownBy(() -> g.start("host")).hasMessageContaining("최소 2명");
    }

    @Test
    void 방장만_시작가능() {
        MojoGame g = new MojoGame("host", "방장", false);
        g.addBot("host", "NORMAL");
        assertThatThrownBy(() -> g.start("intruder")).hasMessageContaining("방장");
    }

    @Test
    void 시작하면_각자_손패8장_차례진행() {
        MojoGame g = new MojoGame("host", "방장", false);
        g.join("p2", "친구");
        g.start("host");
        assertThat(g.phase()).isEqualTo(MojoGame.Phase.PLAYING);
        for (MojoGame.P p : g.playersList()) assertThat(p.hand).hasSize(8);
        assertThat(g.turnSeat()).isEqualTo(0);
        // 내 차례가 아닌 사람은 낼 수 없음
        assertThatThrownBy(() -> g.play("p2", g.playersList().get(1).hand.get(0), 0)).hasMessageContaining("차례");
    }

    @Test
    void 봇들끼리_게임이_끝까지_진행되어_종료된다() {
        MojoGame g = new MojoGame("host", "방장", false);
        g.addBot("host", "NORMAL");
        g.addBot("host", "NORMAL");
        g.start("host"); // host + 봇2 = 3인, host도 봇처럼 자동으로?  host는 사람이므로 시간초과 자동 처리
        int guard = 0;
        while (g.phase() != MojoGame.Phase.ENDED && guard++ < 5000) {
            g.speedUpBotsForTest();
            // host 차례면 사람이므로 자동 안 됨 → 대신 유효한 행동을 대신 수행
            if (g.turnSeat() == 0 && g.phase() == MojoGame.Phase.PLAYING) {
                MojoGame.P host = g.playersList().get(0);
                if (host.inMojo) g.reveal("host");
                else if (!host.hand.isEmpty()) g.play("host", host.hand.get(0), 0);
                else g.me("host");
            } else {
                g.me("host"); // 봇 차례 진행
            }
        }
        assertThat(g.phase()).isEqualTo(MojoGame.Phase.ENDED);
        assertThat(g.winner()).isNotBlank();
        // 누군가 50점 이상
        int max = 0; for (MojoGame.P p : g.playersList()) max = Math.max(max, p.total);
        assertThat(max).isGreaterThanOrEqualTo(50);
    }

    @Test
    void 높은카드를_내면_뽑기에서_1장_뽑는다() {
        MojoGame g = new MojoGame("host", "방장", false);
        g.join("p2", "친구");
        g.start("host");
        MojoGame.P host = g.playersList().get(0);
        int top = g.me("host").discardTops().get(0);
        // host 손패에서 top보다 높은 카드 찾기
        Integer high = null;
        for (int v : host.hand) if (v > top) { high = v; break; }
        if (high == null) return; // 이 판엔 높은 카드가 없으면 스킵(랜덤)
        int handBefore = host.hand.size();
        int drawBefore = g.me("host").drawCount();
        g.play("host", high, 0);
        // 높은 카드 냄 → 1장 뽑아서 손패 수 유지, 뽑기 더미 1 감소
        assertThat(host.hand.size()).isEqualTo(handBefore);
        assertThat(g.me("host").drawCount()).isEqualTo(drawBefore - 1);
    }

    @Test
    void 낮은카드를_내면_뽑지_않고_손패가_준다() {
        MojoGame g = new MojoGame("host", "방장", false);
        g.join("p2", "친구");
        g.start("host");
        MojoGame.P host = g.playersList().get(0);
        int top = g.me("host").discardTops().get(0);
        Integer low = null;
        for (int v : host.hand) if (v < top) { low = v; break; }
        if (low == null) return;
        int handBefore = host.hand.size();
        int drawBefore = g.me("host").drawCount();
        g.play("host", low, 0);
        assertThat(host.hand.size()).isEqualTo(handBefore - 1);
        assertThat(g.me("host").drawCount()).isEqualTo(drawBefore);
    }

    @Test
    void 이중더미_변형은_버림더미_2개() {
        MojoGame g = new MojoGame("host", "방장", true);
        g.join("p2", "친구");
        g.start("host");
        assertThat(g.me("host").discardTops()).hasSize(2);
    }
}
