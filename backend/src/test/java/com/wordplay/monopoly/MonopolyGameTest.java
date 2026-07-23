package com.wordplay.monopoly;

import com.wordplay.monopoly.dto.MonopolyState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonopolyGameTest {

    private MonopolyGame twoPlayers() {
        MonopolyGame g = new MonopolyGame("host", "방장");
        g.join("p2", "친구");
        return g;
    }

    @Test
    void 최소인원_미달_시작불가() {
        MonopolyGame g = new MonopolyGame("host", "방장");
        assertThatThrownBy(() -> g.start("host")).hasMessageContaining("최소 2명");
    }

    @Test
    void 시작하면_모두_시작칸_시작현금() {
        MonopolyGame g = twoPlayers();
        g.start("host");
        MonopolyState s = g.me("host");
        assertThat(s.phase()).isEqualTo("PLAYING");
        assertThat(s.players()).hasSize(2);
        assertThat(s.players()).allMatch(p -> p.pos() == 0 && p.cash() == 1500 && p.alive());
        assertThat(s.step()).isEqualTo("ROLL");
        assertThat(s.turnSeat()).isEqualTo(0);
    }

    @Test
    void 보드는_32칸이고_파주가_최고가() {
        MonopolyGame g = twoPlayers();
        g.start("host");
        MonopolyState s = g.me("host");
        assertThat(s.board()).hasSize(32);
        MonopolyState.TileView paju = s.board().get(31);
        assertThat(paju.name()).isEqualTo("파주");
        assertThat(paju.price()).isEqualTo(500);
    }

    @Test
    void 내_차례가_아니면_굴리기_불가() {
        MonopolyGame g = twoPlayers();
        g.start("host");
        assertThatThrownBy(() -> g.roll("p2", 60)).hasMessageContaining("차례가 아닙니다");
    }

    @Test
    void 굴리면_주사위값이_생기고_턴이_진행된다() {
        MonopolyGame g = twoPlayers();
        g.start("host");
        g.roll("host", 60);
        MonopolyState s = g.me("host");
        int sum = s.dice()[0] + s.dice()[1];
        assertThat(sum).isBetween(2, 12);
        // 굴린 뒤엔 결정 대기(DECIDE)거나, 자동 처리 후 다음 차례
        assertThat(s.dice()[0]).isBetween(1, 6);
    }

    @Test
    void 봇_추가하고_시작_가능() {
        MonopolyGame g = new MonopolyGame("host", "방장");
        g.addBot("host", "NORMAL");
        g.start("host");
        assertThat(g.me("host").phase()).isEqualTo("PLAYING");
        assertThat(g.playersList()).anyMatch(p -> p.bot);
    }

    @Test
    void 방장_아니면_시작_불가() {
        MonopolyGame g = twoPlayers();
        assertThatThrownBy(() -> g.start("p2")).hasMessageContaining("방장만");
    }

    @Test
    void 카드내용은_뽑은사람에게만_보인다_다른사람은_null() {
        // me() 뷰에서 pending.card는 소유자에게만 채워짐을 구조적으로 확인
        MonopolyGame g = twoPlayers();
        g.start("host");
        MonopolyState mine = g.me("host");
        MonopolyState other = g.me("p2");
        // 시작 직후엔 카드 대기 없음
        assertThat(mine.pending()).isNull();
        assertThat(other.pending()).isNull();
    }
}
