package com.wordplay.sixnimmt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SixNimmtGameTest {

    private static void setHand(SixNimmtGame.P p, int... cards) { p.hand.clear(); for (int c : cards) p.hand.add(c); p.selected = -1; }
    private static void setRows(SixNimmtGame g, int[]... rs) { g.rows().clear(); for (int[] r : rs) { List<Integer> row = new java.util.ArrayList<>(); for (int c : r) row.add(c); g.rows().add(row); } }

    private SixNimmtGame twoHumans() {
        SixNimmtGame g = new SixNimmtGame("A", "알파", "POINTS", null);
        g.join("B", "베타");
        g.start("A"); // 무작위 배분됨 → 아래에서 통제된 상태로 덮어씀
        return g;
    }

    @Test
    void 벌점_황소머리_규칙() {
        assertThat(SixNimmtGame.bulls(55)).isEqualTo(7);
        assertThat(SixNimmtGame.bulls(11)).isEqualTo(5);
        assertThat(SixNimmtGame.bulls(99)).isEqualTo(5);
        assertThat(SixNimmtGame.bulls(10)).isEqualTo(3);
        assertThat(SixNimmtGame.bulls(100)).isEqualTo(3);
        assertThat(SixNimmtGame.bulls(45)).isEqualTo(2);
        assertThat(SixNimmtGame.bulls(5)).isEqualTo(2);
        assertThat(SixNimmtGame.bulls(1)).isEqualTo(1);
        assertThat(SixNimmtGame.bulls(7)).isEqualTo(1);
    }

    @Test
    void 여섯번째_카드를_놓으면_그_줄을_벌점으로_회수한다() {
        SixNimmtGame g = twoHumans();
        var A = g.playersForTest().get(0); var B = g.playersForTest().get(1);
        setRows(g, new int[]{1}, new int[]{20, 21, 22, 23, 24}, new int[]{30}, new int[]{40});
        setHand(A, 25, 99);   // 25는 24 뒤 → 줄2의 6번째
        setHand(B, 41, 98);   // 41은 40 뒤 → 줄3에 안전 배치

        g.play("A", 25);
        g.play("B", 41);      // 전원 선택 → 오름차순(25 먼저) 자동 배치

        // 20+21+22+23+24 = 3+1+5+1+1 = 11
        assertThat(A.penalty).isEqualTo(11);
        assertThat(g.rows().get(1)).containsExactly(25);        // 회수 후 25가 새 시작
        assertThat(g.rows().get(3)).containsExactly(40, 41);    // 41은 그냥 붙음
    }

    @Test
    void 모든_줄보다_낮으면_줄을_골라_회수한다() {
        SixNimmtGame g = twoHumans();
        var A = g.playersForTest().get(0); var B = g.playersForTest().get(1);
        setRows(g, new int[]{10}, new int[]{20}, new int[]{30}, new int[]{40});
        setHand(A, 5, 99);    // 5는 모든 줄(10,20,30,40)보다 작음 → 줄 선택 필요
        setHand(B, 11, 98);

        g.play("A", 5);
        g.play("B", 11);
        assertThat(g.phase()).isEqualTo(SixNimmtGame.Phase.CHOOSE_ROW);
        assertThat(g.chooserSeat()).isEqualTo(0);               // A가 골라야 함

        g.takeRow("A", 0);                                      // 줄0([10], 벌점3) 회수
        assertThat(A.penalty).isEqualTo(3);
        assertThat(g.rows().get(0)).containsExactly(5, 11);     // 5가 새 시작, 11이 뒤에 붙음
    }

    @Test
    void 봇만으로_한판을_끝낼_수_있다() {
        SixNimmtGame g = new SixNimmtGame("A", "알파", "HANDS", 1); // 1판만
        g.addBot("A", "NORMAL");
        g.addBot("A", "HARD");
        g.start("A");
        long guard = 0;
        while (g.phase() != SixNimmtGame.Phase.ENDED && guard++ < 500) {
            if (g.phase() == SixNimmtGame.Phase.SELECT) {
                var A = g.playersForTest().get(0);
                if (A.selected == -1 && !A.hand.isEmpty()) g.play("A", A.hand.get(0));
            } else if (g.phase() == SixNimmtGame.Phase.CHOOSE_ROW && g.chooserSeat() == 0) {
                g.takeRow("A", 0);
            }
            g.speedUpBotsForTest();
            g.tick();
        }
        assertThat(g.phase()).isEqualTo(SixNimmtGame.Phase.ENDED);
        assertThat(g.winner()).isNotNull();
    }
}
