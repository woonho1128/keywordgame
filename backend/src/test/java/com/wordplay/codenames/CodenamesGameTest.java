package com.wordplay.codenames;

import com.wordplay.codenames.dto.CodenamesStateResponse;
import com.wordplay.codenames.dto.CodenamesStateResponse.Cell;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodenamesGameTest {

    /** 4인 2:2, 각 팀 스파이마스터 1명 지정 후 시작. */
    private CodenamesGame start4() {
        CodenamesGame g = new CodenamesGame();
        g.newGame("host", "방장");
        g.join("c1", "p1");
        g.join("c2", "p2");
        g.join("c3", "p3");
        g.setTeam("host", "RED");
        g.claimSpymaster("host");   // RED 스파이마스터
        g.setTeam("c1", "RED");     // RED 요원
        g.setTeam("c2", "BLUE");
        g.claimSpymaster("c2");     // BLUE 스파이마스터
        g.setTeam("c3", "BLUE");    // BLUE 요원
        g.start("host");
        return g;
    }

    /** 스파이마스터는 host, 활성팀 요원 클라이언트를 찾는다. */
    private String operativeOf(CodenamesGame g, String team) {
        for (String c : List.of("host", "c1", "c2", "c3")) {
            CodenamesStateResponse s = g.me(c);
            if (team.equals(s.myTeam()) && !s.amSpymaster()) return c;
        }
        throw new IllegalStateException("no operative for " + team);
    }

    private String spymasterOf(CodenamesGame g, String team) {
        for (String c : List.of("host", "c1", "c2", "c3")) {
            CodenamesStateResponse s = g.me(c);
            if (team.equals(s.myTeam()) && s.amSpymaster()) return c;
        }
        throw new IllegalStateException("no spymaster for " + team);
    }

    @Test
    void 시작_보드_9_8_7_1() {
        CodenamesGame g = start4();
        CodenamesStateResponse s = g.me(spymasterOf(g, g.me("host").currentTeam()));
        assertThat(s.status()).isEqualTo("CLUE");
        // 스파이마스터에겐 모든 색이 보인다
        long red = s.board().stream().filter(c -> "RED".equals(c.color())).count();
        long blue = s.board().stream().filter(c -> "BLUE".equals(c.color())).count();
        long neutral = s.board().stream().filter(c -> "NEUTRAL".equals(c.color())).count();
        long assassin = s.board().stream().filter(c -> "ASSASSIN".equals(c.color())).count();
        assertThat(red + blue).isEqualTo(17);
        assertThat(neutral).isEqualTo(7);
        assertThat(assassin).isEqualTo(1);
        assertThat(Math.max(red, blue)).isEqualTo(9);
        assertThat(Math.min(red, blue)).isEqualTo(8);
    }

    @Test
    void 요원에겐_미공개칸_색이_안보인다() {
        CodenamesGame g = start4();
        String op = operativeOf(g, g.me("host").currentTeam());
        CodenamesStateResponse s = g.me(op);
        assertThat(s.board()).allMatch(c -> c.color() == null); // 아직 공개 전
    }

    @Test
    void 힌트후_추측하면_공개된다() {
        CodenamesGame g = start4();
        String team = g.me("host").currentTeam();
        g.clue(spymasterOf(g, team), "과일", 2);
        assertThat(g.me("host").status()).isEqualTo("GUESS");

        // 스파이마스터 시야로 자기 팀 색 칸 하나를 골라 요원이 추측 → 정답, 계속 진행
        CodenamesStateResponse spyView = g.me(spymasterOf(g, team));
        int ownCell = spyView.board().stream().filter(c -> team.equals(c.color())).findFirst().orElseThrow().index();
        CodenamesStateResponse after = g.guess(operativeOf(g, team), ownCell);
        assertThat(after.board().get(ownCell).revealed()).isTrue();
    }

    @Test
    void 암살자_건드리면_상대팀_승리() {
        CodenamesGame g = start4();
        String team = g.me("host").currentTeam();
        g.clue(spymasterOf(g, team), "테스트", 3);
        int assassin = g.me(spymasterOf(g, team)).board().stream()
                .filter(c -> "ASSASSIN".equals(c.color())).findFirst().orElseThrow().index();
        CodenamesStateResponse end = g.guess(operativeOf(g, team), assassin);
        assertThat(end.status()).isEqualTo("ENDED");
        assertThat(end.winner()).isEqualTo("RED".equals(team) ? "BLUE" : "RED");
    }

    @Test
    void 스파이마스터_없으면_시작불가() {
        CodenamesGame g = new CodenamesGame();
        g.newGame("host", "방장");
        g.join("c1", "p1");
        g.join("c2", "p2");
        g.join("c3", "p3");
        g.setTeam("host", "RED");
        g.setTeam("c1", "RED");
        g.setTeam("c2", "BLUE");
        g.setTeam("c3", "BLUE");
        assertThatThrownBy(() -> g.start("host")).hasMessageContaining("팀장");
    }
}
