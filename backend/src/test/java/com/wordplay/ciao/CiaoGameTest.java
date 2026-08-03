package com.wordplay.ciao;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CiaoGameTest {

    private CiaoGame twoHumans() {
        CiaoGame g = new CiaoGame("h", "호스트", 8);
        g.join("b", "친구");
        g.start("h");
        return g;
    }
    private CiaoGame.P cur(CiaoGame g) { return g.playersList().get(g.turnSeat()); }
    private CiaoGame.P other(CiaoGame g) { return g.playersList().get((g.turnSeat() + 1) % 2); }

    @Test
    void 인원별_보정_2인은_원작_7말_3건넘() {
        CiaoGame g = twoHumans();
        assertThat(g.pawnsPer()).isEqualTo(7);
        assertThat(g.goal()).isEqualTo(3);
        for (CiaoGame.P p : g.playersList()) {
            assertThat(p.pawnsLeft).isEqualTo(7);
            assertThat(p.crossed).isZero();
            assertThat(p.bridgePos).isZero();
        }
    }

    @Test
    void 인원별_보정_7인은_5말_2건넘_9인은_4말_2건넘() {
        CiaoGame g7 = new CiaoGame("h", "호스트", 8);
        for (int i = 0; i < 6; i++) g7.addBot("h", "NORMAL");
        g7.start("h");
        assertThat(g7.pawnsPer()).isEqualTo(5);
        assertThat(g7.goal()).isEqualTo(2);

        CiaoGame g9 = new CiaoGame("h", "호스트", 8);
        for (int i = 0; i < 8; i++) g9.addBot("h", "NORMAL");
        g9.start("h");
        assertThat(g9.pawnsPer()).isEqualTo(4);
        assertThat(g9.goal()).isEqualTo(2);
    }

    @Test
    void 거짓말_의심_성공시_선언자_추락_의심자_선언값_전진() {
        CiaoGame g = twoHumans();
        CiaoGame.P liar = cur(g), catcher = other(g);
        g.setRollForTest(2);
        g.declare(liar.clientId, 3); // 실제 2, 선언 3 → 거짓말
        g.challenge(catcher.clientId);
        assertThat(liar.pawnsLeft).isEqualTo(6);   // 말 1개 추락
        assertThat(liar.bridgePos).isZero();
        assertThat(catcher.pawnsLeft).isEqualTo(7);
        assertThat(catcher.bridgePos).isEqualTo(3); // 선언 값만큼 전진
    }

    @Test
    void 진실_의심_실패시_의심자_추락_선언자_실제값_전진() {
        CiaoGame g = twoHumans();
        CiaoGame.P honest = cur(g), doubter = other(g);
        g.setRollForTest(3);
        g.declare(honest.clientId, 3); // 진실
        g.challenge(doubter.clientId);
        assertThat(doubter.pawnsLeft).isEqualTo(6);
        assertThat(honest.pawnsLeft).isEqualTo(7);
        assertThat(honest.bridgePos).isEqualTo(3);
    }

    @Test
    void X는_어떤_선언이든_거짓말() {
        CiaoGame g = twoHumans();
        CiaoGame.P liar = cur(g), catcher = other(g);
        g.setRollForTest(0); // X
        g.declare(liar.clientId, 4);
        g.challenge(catcher.clientId);
        assertThat(liar.pawnsLeft).isEqualTo(6);
        assertThat(catcher.bridgePos).isEqualTo(4);
    }

    @Test
    void 의심없이_지나가면_선언값대로_전진하고_턴이_넘어간다() {
        CiaoGame g = twoHumans();
        CiaoGame.P p = cur(g);
        int seatBefore = g.turnSeat();
        g.setRollForTest(1);
        g.declare(p.clientId, 4); // 뻥이지만 아무도 의심 안 함
        g.expireChallengeForTest();
        assertThat(p.bridgePos).isEqualTo(4); // 선언 값 그대로
        assertThat(g.turnSeat()).isNotEqualTo(seatBefore);
    }

    @Test
    void 다리를_목표만큼_건너면_즉시_승리() {
        CiaoGame g = twoHumans();
        CiaoGame.P p = cur(g);
        p.bridgePos = 8; p.crossed = g.goal() - 1;
        g.setRollForTest(3);
        g.declare(p.clientId, 3);
        g.expireChallengeForTest(); // 8+3=11 > 10 → 건넘 → 목표 달성
        assertThat(g.phase()).isEqualTo(CiaoGame.Phase.ENDED);
        assertThat(g.winnerSeat()).isEqualTo(p.seat);
    }

    /**
     * 목표가 3개인데 남은 말로 3개를 채울 수 없으면 이미 진 것이므로 그 자리에서 탈락해야 한다.
     * (말이 0개가 될 때까지 계속 플레이되던 문제)
     */
    /** 사람 3명 방(봇은 clientId가 없어 직접 호출할 수 없다). */
    private CiaoGame threeHumans() {
        CiaoGame g = new CiaoGame("h", "호스트", 8);
        g.join("b", "친구1");
        g.join("c", "친구2");
        g.start("h");
        return g;
    }
    /** 현재 차례가 아닌 아무 사람. */
    private CiaoGame.P someoneElse(CiaoGame g, CiaoGame.P not) {
        for (CiaoGame.P p : g.playersList()) if (p != not) return p;
        throw new IllegalStateException();
    }

    @Test
    void 남은_말로_목표를_채울_수_없으면_즉시_탈락한다() {
        CiaoGame g = threeHumans();
        CiaoGame.P a = cur(g), b = someoneElse(g, a);
        assertThat(g.goal()).isEqualTo(3);

        a.crossed = 0; a.pawnsLeft = 3;      // 3개 남음 → 아직 가능(3개 다 건너면 달성)
        g.setRollForTest(2);
        g.declare(a.clientId, 4);            // 거짓말
        g.challenge(b.clientId);             // a 말 1개 추락 → 2개 남음 → 목표 불가
        assertThat(a.pawnsLeft).isEqualTo(2);
        assertThat(a.eliminated).as("남은 말 2개로는 3개를 못 채우므로 탈락").isTrue();
    }

    @Test
    void 이미_건넌_말이_있으면_남은_말이_적어도_계속한다() {
        CiaoGame g = threeHumans();
        CiaoGame.P a = cur(g), b = someoneElse(g, a);

        a.crossed = 2; a.pawnsLeft = 2;      // 2개 건넜고 2개 남음 → 1개만 더 건너면 승리
        g.setRollForTest(2);
        g.declare(a.clientId, 4);
        g.challenge(b.clientId);             // 1개 추락 → 남은 1개, 건넌 2개 = 3 달성 가능
        assertThat(a.pawnsLeft).isEqualTo(1);
        assertThat(a.eliminated).as("2+1 = 3 이므로 아직 가능").isFalse();
    }

    @Test
    void 말이_모두_사라지면_탈락하고_남은_한명이_승리() {
        CiaoGame g = twoHumans();
        CiaoGame.P a = cur(g), b = other(g);
        a.pawnsLeft = 1; // 다음 추락이 마지막 말
        g.setRollForTest(2);
        g.declare(a.clientId, 4); // 거짓말
        g.challenge(b.clientId);  // a 추락 → 전멸 → b 승리
        assertThat(a.eliminated).isTrue();
        assertThat(g.phase()).isEqualTo(CiaoGame.Phase.ENDED);
        assertThat(g.winnerSeat()).isEqualTo(b.seat);
    }
}
