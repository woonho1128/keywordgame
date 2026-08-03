package com.wordplay.spicy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpicyGameTest {

    private SpicyGame withPlayers(int n) {
        SpicyGame g = new SpicyGame("host", "방장", 8, null);
        for (int i = 1; i < n; i++) g.join("p" + i, "친구" + i);
        return g;
    }
    private SpicyGame.P cur(SpicyGame g) { return g.playersList().get(g.turnSeat()); }
    private SpicyGame.P other(SpicyGame g, SpicyGame.P not) {
        for (SpicyGame.P p : g.playersList()) if (p != not) return p;
        throw new IllegalStateException();
    }
    /**
     * 특정 카드를 손에 쥐여준다. 덱에 없으면(셔플 결과 이미 누군가 쥐고 있으면) 그 손패에서 옮겨온다 —
     * 덱만 뒤지면 셔플에 따라 간헐적으로 실패한다.
     */
    private SpicyGame.Card giveCard(SpicyGame g, SpicyGame.P p, int spice, int number) {
        for (SpicyGame.Card c : g.drawForTest()) {
            if (c.spice() == spice && c.number() == number) {
                g.drawForTest().remove(c); p.hand.add(c); return c;
            }
        }
        for (SpicyGame.P q : g.playersList()) {
            for (SpicyGame.Card c : q.hand) {
                if (c.spice() == spice && c.number() == number) {
                    q.hand.remove(c); if (q != p) p.hand.add(c); return c;
                }
            }
        }
        throw new IllegalStateException("어디에도 없음: " + spice + "/" + number);
    }

    @Test
    void 인원별_카드_구성이_원작_비율을_유지한다() {
        // 6인까지 원작 100장(+종료 카드), 7~8인 134장, 9~10인 166장
        SpicyGame g6 = withPlayers(6); g6.start("host");
        assertThat(g6.deckSizeForTest()).isEqualTo(100);
        assertThat(g6.trophyTotalForTest()).isEqualTo(3);

        SpicyGame g8 = new SpicyGame("host", "방장", 8, null);
        for (int i = 0; i < 7; i++) g8.addBot("host", "NORMAL");
        g8.start("host");
        assertThat(g8.deckSizeForTest()).isEqualTo(134);
        assertThat(g8.trophyTotalForTest()).isEqualTo(4);

        SpicyGame g10 = new SpicyGame("host", "방장", 8, null);
        for (int i = 0; i < 9; i++) g10.addBot("host", "NORMAL");
        g10.start("host");
        assertThat(g10.deckSizeForTest()).isEqualTo(166);
        assertThat(g10.trophyTotalForTest()).isEqualTo(5);
    }

    @Test
    void 첫_카드는_1에서_3만_선언할_수_있다() {
        SpicyGame g = withPlayers(3); g.start("host");
        assertThat(g.playableNumbers()).containsExactly(1, 2, 3);
        assertThat(g.playableSpices()).containsExactly(0, 1, 2);   // 아무 스파이스나
    }

    @Test
    void 진행_중에는_같은_스파이스의_더_큰_숫자만_선언할_수_있다() {
        SpicyGame g = withPlayers(3); g.start("host");
        g.setDeclaredForTest(1, 4);
        assertThat(g.playableNumbers()).containsExactly(5, 6, 7, 8, 9, 10);
        assertThat(g.playableSpices()).containsExactly(1);          // 같은 스파이스만
    }

    /** 블로그 룰: 10에 도달하면 다음 사람부터 '같은 스파이스나 다른 스파이스'의 1~3. */
    @Test
    void _10_다음에는_아무_스파이스나_1에서_3으로_다시_시작한다() {
        SpicyGame g = withPlayers(3); g.start("host");
        g.setDeclaredForTest(2, 10);
        assertThat(g.playableNumbers()).containsExactly(1, 2, 3);
        assertThat(g.playableSpices()).containsExactly(0, 1, 2);
    }

    @Test
    void 거짓말이어도_엉뚱한_쪽을_지목하면_도전자가_진다() {
        SpicyGame g = withPlayers(2); g.start("host");
        SpicyGame.P liar = cur(g), doubter = other(g, liar);
        SpicyGame.Card c = giveCard(g, liar, 0, 7);          // 실제: 고추 7
        g.play(liar.clientId, c.id(), 0, 2);                 // 선언: 고추 2 (숫자만 거짓)

        int wonBefore = liar.won;
        g.challenge(doubter.clientId, "SPICE");              // 스파이스를 의심 → 고추 맞음 → 실패
        assertThat(liar.won).isEqualTo(wonBefore + 1);       // 낸 사람이 더미 획득
        assertThat(doubter.hand).hasSizeGreaterThan(6 - 1);  // 도전 패자는 2장 더 받음
    }

    @Test
    void 정확히_지목하면_도전자가_더미를_가져간다() {
        SpicyGame g = withPlayers(2); g.start("host");
        SpicyGame.P liar = cur(g), doubter = other(g, liar);
        SpicyGame.Card c = giveCard(g, liar, 0, 7);
        g.play(liar.clientId, c.id(), 0, 2);                 // 숫자가 거짓
        g.challenge(doubter.clientId, "NUMBER");             // 숫자를 의심 → 성공
        assertThat(doubter.won).isEqualTo(1);
    }

    /** 블로그 룰: 모든 숫자 카드는 스파이스 도전에 항상 지고, 숫자 도전에는 항상 이긴다. */
    @Test
    void 모든_숫자_카드는_스파이스_도전에_항상_진다() {
        SpicyGame g = withPlayers(2); g.start("host");
        SpicyGame.P liar = cur(g), doubter = other(g, liar);
        SpicyGame.Card wild = giveCard(g, liar, SpicyGame.NONE, SpicyGame.ALL);   // 모든 숫자 카드
        g.play(liar.clientId, wild.id(), 0, 3);

        g.challenge(doubter.clientId, "SPICE");              // 스파이스가 없으니 도전 성공
        assertThat(doubter.won).isEqualTo(1);
    }

    @Test
    void 모든_숫자_카드는_숫자_도전에_항상_이긴다() {
        SpicyGame g = withPlayers(2); g.start("host");
        SpicyGame.P liar = cur(g), doubter = other(g, liar);
        SpicyGame.Card wild = giveCard(g, liar, SpicyGame.NONE, SpicyGame.ALL);
        g.play(liar.clientId, wild.id(), 0, 3);

        g.challenge(doubter.clientId, "NUMBER");             // 어떤 숫자든 맞으니 도전 실패
        assertThat(liar.won).isEqualTo(1);
    }

    @Test
    void 모든_스파이스_카드는_숫자_도전에_항상_진다() {
        SpicyGame g = withPlayers(2); g.start("host");
        SpicyGame.P liar = cur(g), doubter = other(g, liar);
        SpicyGame.Card wild = giveCard(g, liar, SpicyGame.ALL, SpicyGame.NONE);   // 모든 스파이스 카드
        g.play(liar.clientId, wild.id(), 1, 2);

        g.challenge(doubter.clientId, "SPICE");              // 어떤 스파이스든 맞으니 도전 실패
        assertThat(liar.won).isEqualTo(1);
    }

    @Test
    void 패스하면_카드를_한_장_가져가고_차례가_넘어간다() {
        SpicyGame g = withPlayers(3); g.start("host");
        SpicyGame.P p = cur(g);
        int before = p.hand.size(), seat = g.turnSeat();
        g.pass(p.clientId);
        assertThat(p.hand).hasSize(before + 1);
        assertThat(g.turnSeat()).isNotEqualTo(seat);
    }

    @Test
    void 아무도_도전하지_않으면_더미가_쌓인_채_다음_차례로_간다() {
        SpicyGame g = withPlayers(3); g.start("host");
        SpicyGame.P p = cur(g);
        SpicyGame.Card c = giveCard(g, p, 0, 2);
        g.play(p.clientId, c.id(), 0, 2);
        int seat = g.turnSeat();
        g.forcePassChallengeForTest();
        assertThat(g.pileForTest()).hasSize(1);              // 더미 유지
        assertThat(g.turnSeat()).isNotEqualTo(seat);
        assertThat(g.declaredNumberForTest()).isEqualTo(2);  // 다음 사람은 3 이상을 불러야 함
    }

    @Test
    void 손패를_다_내려놓으면_트로피를_받고_6장을_새로_받는다() {
        SpicyGame g = withPlayers(2); g.start("host");
        SpicyGame.P p = cur(g);
        p.hand.clear();
        SpicyGame.Card c = giveCard(g, p, 0, 1);
        g.play(p.clientId, c.id(), 0, 1);                    // 마지막 한 장
        g.forcePassChallengeForTest();                       // 아무도 도전 안 함
        assertThat(p.trophies).isEqualTo(1);
        assertThat(p.hand).hasSize(6);                       // 재충전
    }

    @Test
    void 트로피_2개면_즉시_승리() {
        SpicyGame g = withPlayers(2); g.start("host");
        SpicyGame.P p = cur(g);
        p.trophies = 1;
        p.hand.clear();
        SpicyGame.Card c = giveCard(g, p, 0, 1);
        g.play(p.clientId, c.id(), 0, 1);
        g.forcePassChallengeForTest();
        assertThat(g.phase()).isEqualTo(SpicyGame.Phase.ENDED);
        assertThat(g.winnerSeat()).isEqualTo(p.seat);
    }

    @Test
    void 점수는_획득카드_트로피_손패감점으로_계산된다() {
        SpicyGame withPenalty = new SpicyGame("host", "방장", 8, true);
        withPenalty.join("p1", "친구"); withPenalty.start("host");
        SpicyGame.P a = withPenalty.playersList().get(0);
        a.won = 12; a.trophies = 1;                          // 12 + 10
        while (a.hand.size() > 4) a.hand.remove(a.hand.size() - 1);
        assertThat(withPenalty.score(a)).isEqualTo(12 + 10 - 4);

        SpicyGame noPenalty = new SpicyGame("host", "방장", 8, false);
        noPenalty.join("p1", "친구"); noPenalty.start("host");
        SpicyGame.P b = noPenalty.playersList().get(0);
        b.won = 12; b.trophies = 1;
        assertThat(noPenalty.score(b)).isEqualTo(22);        // 손패 감점 없음
    }

    @Test
    void 규칙에_어긋난_선언은_거부한다() {
        SpicyGame g = withPlayers(2); g.start("host");
        SpicyGame.P p = cur(g);
        SpicyGame.Card c = p.hand.get(0);
        assertThatThrownBy(() -> g.play(p.clientId, c.id(), 0, 5))   // 첫 장인데 5
                .hasMessageContaining("숫자");
        g.setDeclaredForTest(0, 5);
        assertThatThrownBy(() -> g.play(p.clientId, c.id(), 1, 6))   // 다른 스파이스
                .hasMessageContaining("스파이스");
    }

    @Test
    void 자기_카드에는_도전할_수_없다() {
        SpicyGame g = withPlayers(2); g.start("host");
        SpicyGame.P p = cur(g);
        SpicyGame.Card c = giveCard(g, p, 0, 1);
        g.play(p.clientId, c.id(), 0, 1);
        assertThatThrownBy(() -> g.challenge(p.clientId, "NUMBER"))
                .hasMessageContaining("자기 카드");
    }
}
