package com.wordplay.rummikub;

import com.wordplay.rummikub.dto.RummikubStateResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RummikubGameTest {

    private static final List<String> C = List.of("RED", "BLUE", "BLACK", "ORANGE");
    private int tid(int copy, String color, int number) {
        return copy * 52 + C.indexOf(color) * 13 + (number - 1);
    }
    private static final int JOKER = 104;

    // ---------- 세트 검증 ----------
    @Test
    void 런_그룹_조커_검증() {
        RummikubGame g = new RummikubGame();
        // 런: 빨강 1-2-3
        assertThat(g.setValue(List.of(tid(0, "RED", 1), tid(0, "RED", 2), tid(0, "RED", 3)))).isEqualTo(6);
        // 그룹: 5 세 색
        assertThat(g.setValue(List.of(tid(0, "RED", 5), tid(0, "BLUE", 5), tid(0, "BLACK", 5)))).isEqualTo(15);
        // 조커 런: 1,2,+조커(=3)
        assertThat(g.setValue(List.of(tid(0, "RED", 1), tid(0, "RED", 2), JOKER))).isEqualTo(6);
        // 조커 그룹: 5,5,+조커
        assertThat(g.setValue(List.of(tid(0, "RED", 5), tid(0, "BLUE", 5), JOKER))).isEqualTo(15);
    }

    @Test
    void 잘못된_세트는_음수() {
        RummikubGame g = new RummikubGame();
        // 색 섞인 연속 아님
        assertThat(g.setValue(List.of(tid(0, "RED", 1), tid(0, "BLUE", 2), tid(0, "BLACK", 3)))).isLessThan(0);
        // 그룹인데 색 중복
        assertThat(g.setValue(List.of(tid(0, "RED", 5), tid(1, "RED", 5), tid(0, "BLUE", 5)))).isLessThan(0);
        // 런인데 숫자 중복
        assertThat(g.setValue(List.of(tid(0, "RED", 5), tid(1, "RED", 5), tid(0, "RED", 6)))).isLessThan(0);
        // 3개 미만
        assertThat(g.setValue(List.of(tid(0, "RED", 1), tid(0, "RED", 2)))).isLessThan(0);
    }

    // ---------- 게임 흐름 ----------
    private RummikubGame started2() {
        RummikubGame g = new RummikubGame();
        g.newGame("host", "p0");
        g.join("c1", "p1");
        g.start("host");
        return g;
    }

    @SuppressWarnings("unchecked")
    private List<Integer> rackOf(RummikubGame g, int seat) throws Exception {
        Field pf = RummikubGame.class.getDeclaredField("players");
        pf.setAccessible(true);
        List<?> players = (List<?>) pf.get(g);
        Object p = players.get(seat);
        Field rf = p.getClass().getDeclaredField("rack");
        rf.setAccessible(true);
        return (List<Integer>) rf.get(p);
    }

    @Test
    void 시작하면_14개씩_분배() throws Exception {
        RummikubGame g = started2();
        assertThat(rackOf(g, 0)).hasSize(14);
        assertThat(rackOf(g, 1)).hasSize(14);
        assertThat(g.me("host").drawCount()).isEqualTo(106 - 28);
        assertThat(g.me("host").isMyTurn()).isTrue();
    }

    @Test
    void 첫등록_30점_이상이면_성공() throws Exception {
        RummikubGame g = started2();
        List<Integer> rack = rackOf(g, 0);
        rack.clear();
        // 빨강 10-11-12 = 33점 + 여분
        rack.addAll(List.of(tid(0, "RED", 10), tid(0, "RED", 11), tid(0, "RED", 12), tid(0, "BLUE", 1)));

        RummikubStateResponse res = g.play("host",
                List.of(List.of(tid(0, "RED", 10), tid(0, "RED", 11), tid(0, "RED", 12))));
        assertThat(res.table()).hasSize(1);
        assertThat(res.table().get(0)).hasSize(3);
        assertThat(g.me("host").myMelded()).isTrue();
        assertThat(rackOf(g, 0)).containsExactly(tid(0, "BLUE", 1));
    }

    @Test
    void 런은_숫자순으로_정규화되어_저장() throws Exception {
        RummikubGame g = started2();
        List<Integer> rack = rackOf(g, 0);
        rack.clear();
        rack.addAll(List.of(tid(0, "RED", 10), tid(0, "RED", 11), tid(0, "RED", 12)));
        // 뒤죽박죽 순서로 제출해도 테이블엔 10-11-12로 저장돼야 함
        RummikubStateResponse res = g.play("host",
                List.of(List.of(tid(0, "RED", 12), tid(0, "RED", 10), tid(0, "RED", 11))));
        assertThat(res.table().get(0).stream().map(t -> t.number()).toList())
                .containsExactly(10, 11, 12);
    }

    @Test
    void 첫등록_30점_미만이면_거부() throws Exception {
        RummikubGame g = started2();
        List<Integer> rack = rackOf(g, 0);
        rack.clear();
        rack.addAll(List.of(tid(0, "RED", 1), tid(0, "RED", 2), tid(0, "RED", 3)));
        assertThatThrownBy(() -> g.play("host",
                List.of(List.of(tid(0, "RED", 1), tid(0, "RED", 2), tid(0, "RED", 3)))))
                .hasMessageContaining("30점");
    }

    @Test
    void 남의_차례엔_불가() {
        RummikubGame g = started2();
        assertThatThrownBy(() -> g.draw("c1")).hasMessageContaining("차례");
    }

    @Test
    void AI_턴_자동진행() {
        RummikubGame g = new RummikubGame();
        g.newGame("host", "p0");
        g.addAi("host");
        assertThat(g.me("host").playerCount()).isEqualTo(2);
        g.start("host");
        // 사람이 뽑고 턴 종료 → AI가 자동으로 두고 다시 사람 차례(1-based seat 1)
        RummikubStateResponse s = g.draw("host");
        assertThat(s.status()).isIn("PLAYING", "ENDED");
        if (s.status().equals("PLAYING")) assertThat(s.currentSeat()).isEqualTo(1);
    }

    @Test
    void AI_난이도_지정_추가() {
        RummikubGame g = new RummikubGame();
        g.newGame("host", "p0");
        g.addAi("host", "EASY");
        g.addAi("host", "HARD");
        g.addAi("host", "몰라요"); // 알 수 없는 값 → 중급
        RummikubStateResponse s = g.me("host");
        assertThat(s.playerCount()).isEqualTo(4);
        assertThat(s.players().get(1).nick()).contains("초급");
        assertThat(s.players().get(2).nick()).contains("고급");
        assertThat(s.players().get(3).nick()).contains("중급");
    }

    @Test
    void 시간초과시_자동_가져오기() throws Exception {
        RummikubGame g = started2();
        int before = rackOf(g, 0).size();
        Field df = RummikubGame.class.getDeclaredField("turnDeadlineMs");
        df.setAccessible(true);
        df.setLong(g, System.currentTimeMillis() - 1); // 데드라인을 과거로
        RummikubStateResponse s = g.me("host");
        assertThat(rackOf(g, 0)).hasSize(before + 1);   // 자동 가져오기 1장
        assertThat(s.currentSeat()).isEqualTo(2);        // 다음 사람 차례(1-based)
    }

    @Test
    void 최소인원_미달() {
        RummikubGame g = new RummikubGame();
        g.newGame("host", "p0");
        assertThatThrownBy(() -> g.start("host")).hasMessageContaining("최소 2명");
    }
}
