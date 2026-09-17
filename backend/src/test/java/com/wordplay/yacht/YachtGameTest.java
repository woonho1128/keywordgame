package com.wordplay.yacht;

import com.wordplay.yacht.YachtGame.Cat;
import com.wordplay.yacht.dto.YachtState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YachtGameTest {

    @Test
    void 족보_점수_계산() {
        assertThat(YachtGame.scoreOf(Cat.ONES, new int[]{1, 1, 1, 2, 3})).isEqualTo(3);
        assertThat(YachtGame.scoreOf(Cat.SIXES, new int[]{6, 6, 1, 2, 3})).isEqualTo(12);
        assertThat(YachtGame.scoreOf(Cat.THREE_KIND, new int[]{4, 4, 4, 2, 3})).isEqualTo(17);
        assertThat(YachtGame.scoreOf(Cat.FOUR_KIND, new int[]{5, 5, 5, 5, 3})).isEqualTo(23);
        assertThat(YachtGame.scoreOf(Cat.FOUR_KIND, new int[]{5, 5, 5, 2, 3})).isEqualTo(0);
        assertThat(YachtGame.scoreOf(Cat.FULL_HOUSE, new int[]{2, 2, 3, 3, 3})).isEqualTo(25);
        assertThat(YachtGame.scoreOf(Cat.FULL_HOUSE, new int[]{2, 2, 2, 3, 4})).isEqualTo(0);
        assertThat(YachtGame.scoreOf(Cat.SMALL_STRAIGHT, new int[]{1, 2, 3, 4, 4})).isEqualTo(30);
        assertThat(YachtGame.scoreOf(Cat.SMALL_STRAIGHT, new int[]{1, 2, 3, 5, 6})).isEqualTo(0);
        assertThat(YachtGame.scoreOf(Cat.LARGE_STRAIGHT, new int[]{2, 3, 4, 5, 6})).isEqualTo(40);
        assertThat(YachtGame.scoreOf(Cat.YAHTZEE, new int[]{4, 4, 4, 4, 4})).isEqualTo(50);
        assertThat(YachtGame.scoreOf(Cat.CHANCE, new int[]{1, 2, 3, 4, 5})).isEqualTo(15);
    }

    @Test
    void 상단_보너스_63이상이면_35점() {
        var card = new java.util.LinkedHashMap<String, Integer>();
        card.put("ones", 3); card.put("twos", 6); card.put("threes", 9);
        card.put("fours", 12); card.put("fives", 15); card.put("sixes", 18); // 합 63
        assertThat(YachtGame.upperSum(card)).isEqualTo(63);
        assertThat(YachtGame.total(card)).isEqualTo(63 + 35);
    }

    @Test
    void 최소인원_미달_시작불가() {
        YachtGame g = new YachtGame("host", "방장");
        assertThatThrownBy(() -> g.start("host")).hasMessageContaining("최소 2명");
    }

    @Test
    void 방장만_시작가능() {
        YachtGame g = new YachtGame("host", "방장");
        g.addBot("host", "NORMAL");
        assertThatThrownBy(() -> g.start("intruder")).hasMessageContaining("방장");
    }

    @Test
    void 내_차례에만_굴리고_확정_가능() {
        YachtGame g = new YachtGame("host", "방장");
        g.join("p2", "friend");
        g.start("host");
        // 첫 차례는 host(seat0). p2는 액션 불가
        assertThatThrownBy(() -> g.roll("p2")).hasMessageContaining("차례");
        assertThatThrownBy(() -> g.pick("p2", "chance")).hasMessageContaining("차례");
        // host는 굴리고 확정 가능
        g.roll("host");
        g.pick("host", "chance");
        YachtState st = g.me("host");
        // host가 확정하면 다음 차례는 p2
        assertThat(st.turnSeat()).isEqualTo(1);
    }

    @Test
    void 봇만_있는_방은_끝까지_자동진행되어_종료() {
        YachtGame g = new YachtGame("host", "방장");
        g.join("p2", "친구");
        g.addBot("host", "NORMAL");
        g.start("host");
        g.speedUpBotsForTest();
        // host, p2가 자기 차례마다 chance로 빠르게 확정, 봇은 tick으로 자동 진행
        int guard = 0;
        while (g.phase() != YachtGame.Phase.ENDED && guard++ < 500) {
            g.speedUpBotsForTest();
            YachtState st = g.me("host");
            if (!"PLAYING".equals(st.phase())) break;
            int turn = st.turnSeat();
            if (turn < 0) break;
            String turnClient = clientAt(g, turn);
            if (turnClient == null) { g.me("host"); continue; } // 봇 차례 → tick이 처리
            // 사람 차례: 아직 안 채운 첫 족보로 확정
            YachtGame.P p = g.playersList().get(turn);
            String cat = firstOpen(p);
            g.pick(turnClient, cat);
        }
        assertThat(g.phase()).isEqualTo(YachtGame.Phase.ENDED);
        assertThat(g.winner()).isNotNull();
        // 모든 사람이 13칸 채움
        for (YachtGame.P p : g.playersList()) if (!p.bot) assertThat(p.card).hasSize(13);
    }

    private static String clientAt(YachtGame g, int seat) {
        YachtGame.P p = g.playersList().get(seat);
        return p.bot ? null : p.clientId;
    }

    private static String firstOpen(YachtGame.P p) {
        for (Cat c : Cat.values()) if (!p.card.containsKey(c.key)) return c.key;
        return "chance";
    }
}
