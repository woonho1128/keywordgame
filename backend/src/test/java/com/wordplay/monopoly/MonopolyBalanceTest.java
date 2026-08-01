package com.wordplay.monopoly;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 밸런스 회귀 방지.
 * "봇이 남의 땅만 있는 판에서 몇십 분씩 안 죽는다"는 제보를 조사하며 만든 테스트다.
 * 원인은 확률(봇이 도시를 피함)이 아니라 맨땅 통행료가 월급보다 한참 낮았던 경제 밸런스였다.
 */
class MonopolyBalanceTest {

    private static Object get(Object o, String f) throws Exception {
        Field x = MonopolyGame.class.getDeclaredField(f); x.setAccessible(true); return x.get(o);
    }
    private static void set(Object o, String f, Object v) throws Exception {
        Field x = MonopolyGame.class.getDeclaredField(f); x.setAccessible(true); x.set(o, v);
    }
    @SuppressWarnings("unchecked")
    private static List<MonopolyGame.P> players(MonopolyGame g) throws Exception {
        return (List<MonopolyGame.P>) get(g, "players");
    }
    private static void step(MonopolyGame g) throws Exception {
        set(g, "botAt", 0L);
        Method m = MonopolyGame.class.getDeclaredMethod("tick"); m.setAccessible(true); m.invoke(g);
    }

    @Test
    void 봇은_특정_칸을_피하지_않는다() throws Exception {
        int city = 0, moves = 0;
        for (int game = 0; game < 8; game++) {
            MonopolyGame g = new MonopolyGame("h", "호스트", false);
            for (int i = 0; i < 3; i++) g.addBot("h", "NORMAL");
            g.start("h");
            for (MonopolyGame.P p : players(g)) p.bot = true;
            int[] before = new int[4];
            for (int s = 0; s < 1200 && g.phase() == MonopolyGame.Phase.PLAYING; s++) {
                List<MonopolyGame.P> ps = players(g);
                int turn = (int) get(g, "turnSeat");
                for (int i = 0; i < ps.size(); i++) before[i] = ps.get(i).pos;
                step(g);
                MonopolyGame.P cur = ps.get(turn);
                if (cur.pos != before[turn]) { moves++; if ("CITY".equals(MonopolyGame.BOARD[cur.pos].type())) city++; }
            }
        }
        double rate = (double) city / moves;
        // 보드 32칸 중 도시 22칸(68.8%). 순간이동/무인도 때문에 정확히 같진 않지만 크게 벗어나면 안 된다.
        assertThat(moves).isGreaterThan(2000);
        assertThat(rate).isBetween(0.58, 0.78);
    }

    @Test
    void 전_도시를_뺏기면_한_바퀴_수지가_적자다() {
        double avgPrice = 0; int cities = 0;
        for (int i = 0; i < MonopolyGame.N; i++)
            if ("CITY".equals(MonopolyGame.BOARD[i].type())) { avgPrice += MonopolyGame.BOARD[i].price(); cities++; }
        avgPrice /= cities;
        double rollsPerLap = (double) MonopolyGame.N / 7.0;          // 2d6 평균 7
        double cityHitRate = (double) cities / MonopolyGame.N;

        // 맨땅(0단계)만으로도 월급 이상을 뜯어낼 수 있어야 언젠가 파산한다.
        double bareTollPerLap = cityHitRate * avgPrice * MonopolyGame.TOLL_MUL[0] * rollsPerLap;
        assertThat(bareTollPerLap).isGreaterThanOrEqualTo(MonopolyGame.SALARY * 0.95);

        // 건물을 올리면 확실히 적자여야 한다.
        double builtTollPerLap = cityHitRate * avgPrice * MonopolyGame.TOLL_MUL[1] * rollsPerLap;
        assertThat(builtTollPerLap).isGreaterThan(MonopolyGame.SALARY * 1.4);
    }

    /** 세계여행으로 판을 한 바퀴 돌아 출발 칸을 지나치면 월급을 받아야 한다. */
    @Test
    void 세계여행이_출발을_지나면_월급을_준다() throws Exception {
        MonopolyGame g = new MonopolyGame("h", "호스트", false);
        g.addBot("h", "NORMAL");
        g.start("h");
        MonopolyGame.P p = players(g).get(0);
        set(g, "turnSeat", 0);

        // 세계여행(24칸) → 용인(19칸): 뒤 번호이므로 출발을 지나친 것
        p.pos = 24; p.cash = 1000;
        set(g, "pendType", "TRAVEL"); set(g, "step", MonopolyGame.Step.DECIDE);
        g.decide("h", "travel", 19);
        assertThat(p.pos).isEqualTo(19);
        assertThat(p.cash).isEqualTo(1000 + MonopolyGame.SALARY);
    }

    @Test
    void 세계여행이_앞으로만_가면_월급이_없다() throws Exception {
        MonopolyGame g = new MonopolyGame("h", "호스트", false);
        g.addBot("h", "NORMAL");
        g.start("h");
        MonopolyGame.P p = players(g).get(0);
        set(g, "turnSeat", 0);

        // 5칸 → 강릉(25칸): 출발을 지나지 않음
        p.pos = 5; p.cash = 1000;
        set(g, "pendType", "TRAVEL"); set(g, "step", MonopolyGame.Step.DECIDE);
        g.decide("h", "travel", 25);
        assertThat(p.pos).isEqualTo(25);
        assertThat(p.cash).isEqualTo(1000);
    }

    @Test
    void 전_도시를_뺏긴_봇은_결국_파산한다() throws Exception {
        MonopolyGame g = new MonopolyGame("h", "호스트", false);
        g.addBot("h", "NORMAL");
        g.start("h");
        for (MonopolyGame.P p : players(g)) p.bot = true;
        int[] owner = (int[]) get(g, "owner");
        for (int i = 0; i < MonopolyGame.N; i++)
            if ("CITY".equals(MonopolyGame.BOARD[i].type())) owner[i] = 1;  // 전부 상대 소유(맨땅)
        MonopolyGame.P victim = players(g).get(0);
        players(g).get(1).cash = 100000;

        for (int s = 0; s < 4000 && victim.alive && g.phase() == MonopolyGame.Phase.PLAYING; s++) step(g);
        assertThat(victim.alive).isFalse();
    }
}
