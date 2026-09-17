package com.wordplay.halligalli;

import com.wordplay.halligalli.HalliGalliGame.Card;
import com.wordplay.halligalli.HalliGalliGame.Player;
import com.wordplay.halligalli.dto.HalliGalliStateResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HalliGalliGameTest {

    private HalliGalliGame started2() {
        HalliGalliGame g = new HalliGalliGame();
        g.newGame("host", "p0");
        g.join("c1", "p1");
        g.start("host");
        return g;
    }

    @Test
    void 덱은_56장_과일별14장() {
        List<Card> deck = HalliGalliGame.deckForTest();
        assertThat(deck).hasSize(56);
        for (String f : HalliGalliGame.FRUITS) {
            assertThat(deck.stream().filter(c -> c.fruit().equals(f)).count()).isEqualTo(14);
        }
        // 각 과일에 5개짜리 카드는 1장뿐
        long fives = deck.stream().filter(c -> c.count() == 5).count();
        assertThat(fives).isEqualTo(4);
    }

    @Test
    void 시작하면_카드_고르게_분배() {
        HalliGalliGame g = started2();
        int total = g.playerList().stream().mapToInt(p -> p.down.size()).sum();
        assertThat(total).isEqualTo(56);
        assertThat(g.me("host").isMyTurn()).isTrue();
    }

    @Test
    void 남의_차례엔_뒤집기_불가() {
        HalliGalliGame g = started2();
        assertThatThrownBy(() -> g.flip("c1")).hasMessageContaining("차례");
    }

    @Test
    void 정확히_5면_종치면_획득() {
        HalliGalliGame g = started2();
        // 강제 세팅: host 공개더미 top = 바나나 5개, 상대는 공개카드 없음
        Player host = g.playerList().get(0);
        Player p1 = g.playerList().get(1);
        host.down.clear(); host.up.clear();
        p1.down.clear(); p1.up.clear();
        host.up.add(new Card("BANANA", 5));
        host.down.add(new Card("PLUM", 1));  // host가 살아있어 게임이 안 끝남
        p1.down.add(new Card("LIME", 1));

        HalliGalliStateResponse res = g.ring("c1"); // 상대가 종을 침
        // c1이 공개카드(바나나5) 1장 획득 → LIME1 + BANANA5 = 2장
        assertThat(p1.total()).isEqualTo(2);
        assertThat(host.up).isEmpty();
        assertThat(res.lastAction()).contains("종");
    }

    @Test
    void 합이_5가_아니면_벌칙() {
        HalliGalliGame g = started2();
        Player host = g.playerList().get(0);
        Player p1 = g.playerList().get(1);
        host.down.clear(); host.up.clear();
        p1.down.clear(); p1.up.clear();
        host.up.add(new Card("BANANA", 3));   // 합 3 → 종 조건 아님
        host.down.add(new Card("LIME", 2));
        p1.down.add(new Card("LIME", 1));     // 상대가 살아있어야 벌칙 대상
        int p1Before = p1.total();

        g.ring("host"); // 잘못 침 → 상대에게 1장
        assertThat(p1.total()).isEqualTo(p1Before + 1);
    }

    @Test
    void 봇_차례면_생각시간_후_자동_넘김() throws Exception {
        HalliGalliGame g = new HalliGalliGame();
        g.newGame("host", "p0");
        g.addAi("host", "HARD");
        assertThat(g.me("host").playerCount()).isEqualTo(2);
        g.start("host");
        assertThat(g.tick()).isFalse();  // host(사람) 차례 → 봇 무동작
        g.flip("host");                  // 봇(seat1) 차례로 넘어감
        // 뒤집은 카드로 '같은 과일 5개'가 뜨면 tick은 넘기기가 아니라 종 치기를 한다.
        // 어느 쪽이든 봇이 시간이 지나면 스스로 행동해야 하므로 두 타이머를 모두 앞당긴다.
        // (한쪽만 강제하면 셔플 결과에 따라 간헐적으로 실패했다.)
        setLong(g, "flipReadyAt", 1L);
        forceBotRingDeadlines(g);
        assertThat(g.tick()).isTrue();   // 봇이 자동으로 행동(넘기기 또는 종)
    }

    private void setLong(HalliGalliGame g, String field, long v) throws Exception {
        java.lang.reflect.Field f = HalliGalliGame.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setLong(g, v);
    }

    @SuppressWarnings("unchecked")
    private void forceBotRingDeadlines(HalliGalliGame g) throws Exception {
        java.lang.reflect.Field f = HalliGalliGame.class.getDeclaredField("botRingAt");
        f.setAccessible(true);
        java.util.Map<Integer, Long> m = (java.util.Map<Integer, Long>) f.get(g);
        m.replaceAll((seat, at) -> 1L);
    }

    @Test
    void 최소인원_미달() {
        HalliGalliGame g = new HalliGalliGame();
        g.newGame("host", "p0");
        assertThatThrownBy(() -> g.start("host")).hasMessageContaining("최소 2명");
    }
}
