package com.wordplay.ciao;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 봇이 실제로 거짓말을 하는지, 그리고 선언값이 진실 여부를 누설하지 않는지 검증.
 * (예전 고급 봇은 X가 나오면 2~3만 불러서 "고급 봇의 「1」은 무조건 진실"이 되어 공략당했다.)
 */
class CiaoBotBluffTest {

    private static Object get(Object o, String f) throws Exception {
        Field x = CiaoGame.class.getDeclaredField(f); x.setAccessible(true); return x.get(o);
    }
    private static void set(Object o, String f, Object v) throws Exception {
        Field x = CiaoGame.class.getDeclaredField(f); x.setAccessible(true); x.set(o, v);
    }
    @SuppressWarnings("unchecked")
    private static List<CiaoGame.P> players(CiaoGame g) throws Exception { return (List<CiaoGame.P>) get(g, "players"); }

    private static void step(CiaoGame g) throws Exception {
        set(g, "botAt", 0L);
        set(g, "deadline", 0L);
        Method m = CiaoGame.class.getDeclaredMethod("tick"); m.setAccessible(true); m.invoke(g);
    }

    /** 해당 난이도 봇들만으로 판을 돌려 [선언수, 거짓말수, 값별 선언수(1~4), 값별 거짓말수(1~4)] 집계. */
    private int[] collect(String level) throws Exception {
        int[] r = new int[10]; // 0:선언 1:거짓말 2~5:값별선언 6~9:값별거짓말
        for (int game = 0; game < 12; game++) {
            CiaoGame g = new CiaoGame("h", "호스트", 8);
            for (int i = 1; i < 4; i++) g.addBot("h", level);
            g.start("h");
            for (CiaoGame.P p : players(g)) { p.bot = true; p.botLevel = level; }
            for (int s = 0; s < 1500 && g.phase() == CiaoGame.Phase.PLAYING; s++) {
                Object tp = get(g, "turnPhase");
                String before = tp == null ? "" : tp.toString();
                int actual = (int) get(g, "actual");
                step(g);
                Object tp2 = get(g, "turnPhase");
                if ("DECLARE".equals(before) && "CHALLENGE".equals(String.valueOf(tp2))) {
                    int d = (int) get(g, "declared");
                    boolean lie = actual == 0 || actual != d;
                    r[0]++; if (lie) r[1]++;
                    if (d >= 1 && d <= 4) { r[1 + d]++; if (lie) r[5 + d]++; }
                }
            }
        }
        return r;
    }

    @Test
    void 모든_난이도_봇이_거짓말을_한다() throws Exception {
        for (String level : new String[]{"EASY", "NORMAL", "HARD"}) {
            int[] r = collect(level);
            assertThat(r[0]).as(level + " 선언 표본").isGreaterThan(200);
            double lieRate = (double) r[1] / r[0];
            // X(2/6)가 나오면 무조건 거짓말이므로 최소 25%는 나와야 한다.
            assertThat(lieRate).as(level + " 거짓말 비율").isBetween(0.25, 0.75);
        }
    }

    @Test
    void 선언값이_진실_여부를_누설하지_않는다() throws Exception {
        for (String level : new String[]{"EASY", "NORMAL", "HARD"}) {
            int[] r = collect(level);
            for (int d = 1; d <= 4; d++) {
                int said = r[1 + d], lied = r[5 + d];
                if (said < 30) continue; // 표본 부족한 값은 건너뜀
                double lieRate = (double) lied / said;
                // 어떤 선언값도 "무조건 진실"이거나 "무조건 거짓"이면 안 된다.
                assertThat(lieRate).as(level + " 봇의 「" + d + "」 선언 거짓말 확률").isBetween(0.10, 0.90);
            }
        }
    }
}
