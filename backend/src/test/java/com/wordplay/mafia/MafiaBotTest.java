package com.wordplay.mafia;

import com.wordplay.mafia.ai.MafiaBotRuntime;
import com.wordplay.mafia.dto.MafiaStateResponse;
import com.wordplay.mafia.dto.NewMafiaRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 봇 추가/구동 검증. LLM 호출은 동기·결정적 가짜 런타임으로 대체한다.
 */
class MafiaBotTest {

    /** 동기 실행 + 고정 응답 가짜 런타임(테스트용). */
    private static MafiaBotRuntime fakeRuntime() {
        return new MafiaBotRuntime(null) {
            @Override public boolean available() { return true; }
            @Override public void submit(Runnable task) { task.run(); } // 동기
            @Override public String chat(String user) { return "음 난 좀 수상한데"; }
            @Override public String vote(String user) { return "0"; }     // 기권
        };
    }

    @Test
    void 봇추가_대기방에서_최대5명() {
        MafiaService svc = new MafiaService(fakeRuntime());
        svc.newGame("host", new NewMafiaRequest("방장", null, null, null, null, null, null, null));

        svc.addBots(5);
        MafiaStateResponse st = svc.me("host");
        assertThat(st.playerCount()).isEqualTo(6);
        assertThat(st.players().stream().filter(MafiaStateResponse.PlayerView::bot).count()).isEqualTo(5);

        // 6번째 봇은 초과 → 예외
        assertThatThrownBy(() -> svc.addBots(1)).hasMessageContaining("최대");
    }

    @Test
    void 한번에_상한을_넘겨_요청해도_상한까지만_들어간다() {
        MafiaService svc = new MafiaService(fakeRuntime());
        svc.newGame("host", new NewMafiaRequest("방장", null, null, null, null, null, null, null));

        svc.addBots(3);
        svc.addBots(5);   // 남은 자리는 2개뿐
        assertThat(svc.me("host").players().stream()
                .filter(MafiaStateResponse.PlayerView::bot).count()).isEqualTo(5);
    }

    @Test
    void 봇_미설정이면_추가불가() {
        MafiaService svc = new MafiaService(); // 런타임 없음
        svc.newGame("host", new NewMafiaRequest("방장", null, null, null, null, null, null, null));
        assertThatThrownBy(() -> svc.addBots(1)).hasMessageContaining("설정");
    }

    @Test
    void 봇은_밤에_스스로_행동해_밤이_진행된다() throws Exception {
        MafiaService svc = new MafiaService(fakeRuntime());
        svc.newGame("host", new NewMafiaRequest("방장", 20, null, null, null, null, null, null));
        svc.addBots(3);
        svc.start("host");
        assertThat(svc.me("host").status()).isEqualTo("NIGHT");

        // 사람(방장)만 지목하고, 봇 3명은 tick으로 스스로 지목 → 전원 완료되면 아침으로.
        String status = "NIGHT";
        for (int i = 0; i < 60 && !status.equals("MORNING"); i++) {
            MafiaStateResponse st = svc.me("host");
            status = st.status();
            if (status.equals("NIGHT") && st.alive() && !st.selectable().isEmpty() && st.myTarget() <= 0) {
                svc.nightAction("host", st.selectable().get(0));
            }
            Thread.sleep(120);
        }
        assertThat(status).isEqualTo("MORNING");
    }

    @Test
    void 채팅은_낮에만_가능() {
        MafiaService svc = new MafiaService(fakeRuntime());
        svc.newGame("host", new NewMafiaRequest("방장", null, null, null, null, null, null, null));
        // 대기방(LOBBY)에선 채팅 불가
        assertThatThrownBy(() -> svc.sendChat("host", "안녕"))
                .hasMessageContaining("대화할 수 없습니다");
    }
}
