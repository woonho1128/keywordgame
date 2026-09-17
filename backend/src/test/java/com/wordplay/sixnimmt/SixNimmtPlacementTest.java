package com.wordplay.sixnimmt;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SixNimmtPlacementTest {
    private SixNimmtGame two() {
        SixNimmtGame g = new SixNimmtGame("host", "방장", "POINTS", 4);
        g.join("p2", "친구");
        g.start("host");
        return g;
    }
    private void setRow(SixNimmtGame g, int i, Integer... cards) {
        List<Integer> r = g.rows().get(i); r.clear(); r.addAll(Arrays.asList(cards));
    }

    @Test
    void 중간_append_26은_사라지지않고_10_18_26_32가_된다() {
        SixNimmtGame g = two();
        setRow(g, 0, 10, 18);
        setRow(g, 1, 50);
        setRow(g, 2, 60);
        setRow(g, 3, 70);
        var ps = g.playersForTest();
        ps.get(0).selected = 26;
        ps.get(1).selected = 32;
        g.beginResolveForTest();
        assertThat(g.rows().get(0)).containsExactly(10, 18, 26, 32);
    }

    @Test
    void 여섯번째_카드는_줄_회수하고_그카드가_새_시작이_된다() {
        SixNimmtGame g = two();
        setRow(g, 0, 1, 2, 3, 4, 5); // 5장
        setRow(g, 1, 90);
        setRow(g, 2, 95);
        setRow(g, 3, 100);
        var ps = g.playersForTest();
        int before = ps.get(0).penalty;
        ps.get(0).selected = 10; // 10>5 → row0 6번째
        ps.get(1).selected = 11;
        g.beginResolveForTest();
        // row0은 회수되어 [10, 11]로 새로 시작(10 회수 후 11이 append)
        assertThat(g.rows().get(0)).containsExactly(10, 11);
        assertThat(ps.get(0).penalty).isGreaterThan(before); // 벌점 발생
    }
}
