package com.wordplay.coup;

import com.wordplay.coup.CoupGame.Character;
import com.wordplay.coup.dto.CoupStateResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoupGameTest {

    /** 사람 3명(봇 없음)으로 흐름을 결정적으로 제어. */
    private CoupGame started3() {
        CoupGame g = new CoupGame();
        g.newGame("h", "호스트", 15);
        g.join("c1", "P1");
        g.join("c2", "P2");
        g.start("h");
        return g;
    }

    @Test
    void 시작_기본세팅() {
        CoupGame g = started3();
        CoupStateResponse s = g.me("h");
        assertThat(s.status()).isEqualTo("PLAYING");
        assertThat(s.players()).hasSize(3);
        assertThat(s.players()).allSatisfy(p -> { assertThat(p.coins()).isEqualTo(2); assertThat(p.influence()).isEqualTo(2); });
        assertThat(s.currentSeat()).isEqualTo(0);
        assertThat(s.myCards()).hasSize(2);
    }

    @Test
    void 소득_턴넘김() {
        CoupGame g = started3();
        g.act("h", "INCOME", null);
        assertThat(g.coinsForTest(0)).isEqualTo(3);
        CoupStateResponse s = g.me("h");
        assertThat(s.currentSeat()).isEqualTo(1);
        assertThat(s.step()).isEqualTo("CHOOSE_ACTION");
    }

    @Test
    void 쿠_대상_영향력_상실() {
        CoupGame g = started3();
        g.setCoinsForTest(0, 7);
        g.act("h", "COUP", 1);       // 대상 2장 → 카드 선택
        assertThat(g.stepForTest()).isEqualTo(CoupGame.Step.LOSE_CARD);
        g.loseCard("c1", 0);
        assertThat(g.influenceForTest(1)).isEqualTo(1);
        assertThat(g.coinsForTest(0)).isEqualTo(0);
        assertThat(g.me("h").currentSeat()).isEqualTo(1); // 턴 넘어감
    }

    @Test
    void 세금_의심_뻥이면_액터_상실_무효() {
        CoupGame g = started3();
        g.giveCardsForTest(0, Character.CAPTAIN, Character.CAPTAIN); // 공작 없음
        g.act("h", "TAX", null);
        assertThat(g.stepForTest()).isEqualTo(CoupGame.Step.CHALLENGE_ACTION);
        g.respond("c1", "CHALLENGE", null);
        assertThat(g.stepForTest()).isEqualTo(CoupGame.Step.LOSE_CARD);
        g.loseCard("h", 0);
        assertThat(g.influenceForTest(0)).isEqualTo(1);
        assertThat(g.coinsForTest(0)).isEqualTo(2);          // 세금 무효
        assertThat(g.me("h").currentSeat()).isEqualTo(1);
    }

    @Test
    void 세금_의심_진짜면_도전자_상실_적용() {
        CoupGame g = started3();
        g.giveCardsForTest(0, Character.DUKE, Character.CAPTAIN);
        g.act("h", "TAX", null);
        g.respond("c1", "CHALLENGE", null);                  // 도전자가 틀림
        assertThat(g.stepForTest()).isEqualTo(CoupGame.Step.LOSE_CARD);
        g.loseCard("c1", 0);
        assertThat(g.influenceForTest(1)).isEqualTo(1);
        assertThat(g.coinsForTest(0)).isEqualTo(5);          // 세금 +3 적용
    }

    @Test
    void 해외원조_전원통과_적용() {
        CoupGame g = started3();
        g.act("h", "FOREIGN_AID", null);
        assertThat(g.stepForTest()).isEqualTo(CoupGame.Step.BLOCK_ACTION);
        g.respond("c1", "PASS", null);
        g.respond("c2", "PASS", null);
        assertThat(g.coinsForTest(0)).isEqualTo(4);
    }

    @Test
    void 해외원조_공작차단_무효() {
        CoupGame g = started3();
        g.act("h", "FOREIGN_AID", null);
        g.respond("c1", "BLOCK", "DUKE");
        assertThat(g.stepForTest()).isEqualTo(CoupGame.Step.CHALLENGE_BLOCK);
        g.respond("h", "PASS", null);
        g.respond("c2", "PASS", null);
        assertThat(g.coinsForTest(0)).isEqualTo(2);          // 원조 무효
        assertThat(g.me("h").currentSeat()).isEqualTo(1);
    }

    @Test
    void 암살_백작부인_차단_통과하면_대상안전() {
        CoupGame g = started3();
        g.setCoinsForTest(0, 3);
        g.giveCardsForTest(0, Character.ASSASSIN, Character.CAPTAIN);
        g.act("h", "ASSASSINATE", 1);
        assertThat(g.stepForTest()).isEqualTo(CoupGame.Step.CHALLENGE_ACTION);
        g.respond("c1", "PASS", null);
        g.respond("c2", "PASS", null);
        assertThat(g.stepForTest()).isEqualTo(CoupGame.Step.BLOCK_ACTION);
        g.respond("c1", "BLOCK", "CONTESSA");
        g.respond("h", "PASS", null);
        g.respond("c2", "PASS", null);
        assertThat(g.influenceForTest(1)).isEqualTo(2);      // 대상 안전
        assertThat(g.coinsForTest(0)).isEqualTo(0);          // 암살 코인은 소모
    }

    @Test
    void 봇게임_한턴_자동진행() {
        CoupGame g = new CoupGame();
        g.newGame("h", "나", 15);
        g.addBot("h", "NORMAL");
        g.start("h");
        g.act("h", "INCOME", null);   // 내 턴 끝 → 봇 차례
        g.forceDeadlineForTest();
        CoupStateResponse s = g.me("h"); // 봇 자동 진행
        // 봇이 뭔가 했거나 다시 내 차례로 돌아옴(게임이 멈추지 않음)
        assertThat(s.status()).isIn("PLAYING", "ENDED");
    }
}
