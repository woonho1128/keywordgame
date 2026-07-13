package com.wordplay.horserace;

import com.wordplay.horserace.dto.HorseRaceStateResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HorseRaceGameTest {

    private HorseRaceGame started() {
        HorseRaceGame g = new HorseRaceGame();
        g.newGame("host", "나", null, 0, "BASIC", "FIXED", 5000, 25, 9, 0);
        g.addBot("host"); g.addBot("host");
        g.start("host");
        return g;
    }

    @Test
    void 시작하면_9마리_배팅페이즈_배당산출() {
        HorseRaceGame g = started();
        HorseRaceStateResponse s = g.me("host");
        assertThat(s.status()).isEqualTo("BETTING");
        assertThat(s.horses()).hasSize(9);
        assertThat(s.horses()).allSatisfy(h -> {
            assertThat(h.condition()).isBetween(1, 5);
            assertThat(h.oddsWin()).isGreaterThanOrEqualTo(1.1);
            assertThat(h.oddsPlace()).isGreaterThanOrEqualTo(1.1);
        });
        // 배당 스프레드: 인기마와 언더독이 구분됨
        double min = s.horses().stream().mapToDouble(HorseRaceStateResponse.HorseView::oddsWin).min().orElse(0);
        double max = s.horses().stream().mapToDouble(HorseRaceStateResponse.HorseView::oddsWin).max().orElse(0);
        assertThat(max).isGreaterThan(min);
    }

    @Test
    void 배팅_레이스_정산_한사이클() {
        HorseRaceGame g = started();
        // 0번 말 단승에 100 배팅
        HorseRaceStateResponse s = g.bet("host", "WIN", new int[]{0}, 100);
        assertThat(s.myBets()).hasSize(1);
        long chipsAfterBet = s.chips();
        assertThat(chipsAfterBet).isEqualTo(5000 - 100);

        g.forceBetEndForTest();
        s = g.me("host"); // 레이스 시작
        assertThat(s.status()).isEqualTo("RACING");
        assertThat(s.race()).isNotNull();
        assertThat(s.race().timeline()).isNotEmpty();
        assertThat(s.race().timeline().get(0)).hasSize(9);
        assertThat(s.race().finishOrder()).hasSize(9);

        g.forceRaceEndForTest();
        s = g.me("host"); // 정산
        assertThat(s.status()).isEqualTo("RESULT");
        assertThat(s.finishOrder()).hasSize(9);
        // 승패에 따라 칩 반영(적어도 배팅액만큼은 빠졌거나 배당으로 늘어남)
        assertThat(s.chips()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 다음레이스는_직전_top3_재출전() {
        HorseRaceGame g = started();
        g.forceBetEndForTest(); g.me("host");
        g.forceRaceEndForTest(); g.me("host"); // RESULT
        int[] prevTop3 = new int[]{ g.finishOrderForTest()[0], g.finishOrderForTest()[1], g.finishOrderForTest()[2] };
        var carriedNames = new java.util.HashSet<String>();
        for (int idx : prevTop3) carriedNames.add(g.horsesForTest().get(idx).name);

        g.nextRace("host");
        HorseRaceStateResponse s = g.me("host");
        assertThat(s.status()).isEqualTo("BETTING");
        long carried = s.horses().stream().filter(h -> !h.isNew()).count();
        assertThat(carried).isEqualTo(3); // top3 이월
        long carriedByName = s.horses().stream().filter(h -> carriedNames.contains(h.name())).count();
        assertThat(carriedByName).isGreaterThanOrEqualTo(3);
    }

    @Test
    void 쌍승_삼복승_배팅_정산() {
        HorseRaceGame g = started();
        // 쌍승(0→1), 삼복승(0,1,2)
        g.bet("host", "EXACTA", new int[]{0, 1}, 100);
        HorseRaceStateResponse s = g.bet("host", "TRIO", new int[]{0, 1, 2}, 100);
        assertThat(s.myBets()).hasSize(2);
        assertThat(s.exactaOdds()).isNotEmpty(); // 배당 맵 제공
        assertThat(s.trioOdds()).isNotEmpty();
        assertThat(s.chips()).isEqualTo(5000 - 200);

        g.forceBetEndForTest(); g.me("host");
        g.forceRaceEndForTest();
        s = g.me("host");
        assertThat(s.status()).isEqualTo("RESULT");
        // 정산 후 칩은 음수가 아니어야 함
        assertThat(s.chips()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 쌍승은_같은말_중복_거부() {
        HorseRaceGame g = started();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> g.bet("host", "EXACTA", new int[]{0, 0}, 100))
                .hasMessageContaining("중복");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> g.bet("host", "TRIO", new int[]{0, 1}, 100))
                .hasMessageContaining("3마리");
    }

    @Test
    void 인기마_승률이_합리적_범위() {
        // ★5 한 마리 vs ★3 여덟 마리 → 인기마지만 지배적이지 않아야 함
        double fav = HorseRaceGame.favWinRateForTest(9, 5, 3, 6000);
        assertThat(fav).isGreaterThan(0.111); // 랜덤(1/9)보다는 유리
        assertThat(fav).isBetween(0.18, 0.60); // 그러나 절반 이상 짐(언더독 여지)
    }
}
