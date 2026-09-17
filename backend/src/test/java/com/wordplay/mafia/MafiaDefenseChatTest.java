package com.wordplay.mafia;

import com.wordplay.mafia.ai.MafiaBotRuntime;
import com.wordplay.mafia.dto.MafiaStateResponse;
import com.wordplay.mafia.dto.NewMafiaRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 최후변론 채팅과 종료 후 대화 다시보기.
 *
 * <p>재판대에 오른 사람은 죽기 직전인데 지금까지 한 마디도 못 했다. 사형/생존을
 * 봇이 토론 내용으로 판단하게 바뀐 뒤로는 변론이 실제로 판을 뒤집을 수 있다.
 */
class MafiaDefenseChatTest {

    private static MafiaService started() {
        MafiaService svc = new MafiaService();
        svc.newGame("host", new NewMafiaRequest("방장", null, null, null, null, null, null, null));
        for (int i = 1; i < 4; i++) svc.join("p" + i, "사람" + i);
        svc.start("host");
        return svc;
    }

    /** 최후변론 단계로 고정하고 지목된 좌석을 정한다. */
    private static void holdInDefense(MafiaService svc, int accusedSeat) {
        ReflectionTestUtils.setField(svc, "phase", MafiaService.Phase.DEFENSE);
        ReflectionTestUtils.setField(svc, "phaseEndsAt", System.currentTimeMillis() + 600_000L);
        ReflectionTestUtils.setField(svc, "accusedSeat", accusedSeat);
    }

    @Test
    void 최후변론은_지목된_사람만_말할_수_있다() {
        MafiaService svc = started();
        holdInDefense(svc, 1);   // 사람1이 재판대에

        svc.sendChat("p1", "나 진짜 아니야 어제 투표 보면 알잖아");
        assertThat(svc.me("host").chat()).extracting(MafiaStateResponse.ChatView::text)
                .contains("나 진짜 아니야 어제 투표 보면 알잖아");

        assertThatThrownBy(() -> svc.sendChat("host", "변명하지 마"))
                .hasMessageContaining("지목된 사람만");
    }

    @Test
    void 사망자는_변론도_못_한다() {
        MafiaService svc = started();
        List<?> players = (List<?>) ReflectionTestUtils.getField(svc, "players");
        ReflectionTestUtils.setField(players.get(1), "alive", false);
        holdInDefense(svc, 1);

        assertThatThrownBy(() -> svc.sendChat("p1", "억울하다"))
                .hasMessageContaining("사망한 플레이어");
    }

    @Test
    void 사형투표_단계에서는_다시_말할_수_없다() {
        MafiaService svc = started();
        ReflectionTestUtils.setField(svc, "phase", MafiaService.Phase.FINAL_VOTE);
        ReflectionTestUtils.setField(svc, "phaseEndsAt", System.currentTimeMillis() + 600_000L);
        ReflectionTestUtils.setField(svc, "accusedSeat", 1);

        assertThatThrownBy(() -> svc.sendChat("p1", "한 번만 더 들어봐"))
                .hasMessageContaining("대화할 수 없습니다");
    }

    @Test
    void 재판대에_오른_봇은_스스로_변론한다() throws Exception {
        MafiaBotRuntime bot = new MafiaBotRuntime(null) {
            @Override public boolean available() { return true; }
            @Override public void submit(Runnable task) { task.run(); }
            @Override public String chat(String user) { return "어제 나 찍은 둘이 오늘도 같이 움직이잖아"; }
            @Override public String vote(String user) { return "0"; }
        };
        MafiaService svc = new MafiaService(bot);
        svc.newGame("host", new NewMafiaRequest("방장", null, null, null, null, null, null, null));
        svc.addBots(3);
        svc.start("host");

        holdInDefense(svc, 1);   // 1번 좌석 = 첫 봇
        ReflectionTestUtils.invokeMethod(svc, "initBotDefense");

        for (int i = 0; i < 60; i++) {
            if (!svc.me("host").chat().isEmpty()) break;
            Thread.sleep(100);
        }
        assertThat(svc.me("host").chat()).extracting(MafiaStateResponse.ChatView::text)
                .contains("어제 나 찍은 둘이 오늘도 같이 움직이잖아");
    }

    @Test
    void 게임이_끝나면_60줄을_넘겨도_전체_기록을_준다() {
        MafiaService svc = started();
        ReflectionTestUtils.setField(svc, "phase", MafiaService.Phase.DISCUSS);
        ReflectionTestUtils.setField(svc, "phaseEndsAt", System.currentTimeMillis() + 600_000L);
        for (int i = 0; i < 70; i++) svc.sendChat("host", "발언 " + i);

        // 진행 중에는 최근 60줄만
        assertThat(svc.me("host").chat()).hasSize(60);

        ReflectionTestUtils.setField(svc, "phase", MafiaService.Phase.ENDED);
        ReflectionTestUtils.setField(svc, "winner", "CITIZEN");
        List<MafiaStateResponse.ChatView> all = svc.me("host").chat();
        assertThat(all).hasSize(70);
        assertThat(all.get(0).text()).isEqualTo("발언 0");   // 첫 줄까지 남아 있어야 다시보기가 된다
    }
}
