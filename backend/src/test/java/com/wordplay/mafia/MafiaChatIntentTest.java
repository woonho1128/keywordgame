package com.wordplay.mafia;

import com.wordplay.mafia.ai.MafiaBotRuntime;
import com.wordplay.mafia.dto.MafiaStateResponse;
import com.wordplay.mafia.dto.NewMafiaRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 봇 발언 프롬프트에 "이번에 뭘 할지"가 들어가는지.
 *
 * <p>실제 판에서 봇들이 근거 없이 "너 수상해"만 돌려 말하고, 지목당해도 답을 안 하고
 * 화제를 돌린다는 지적이 있었다. 매 발언마다 목적을 지정해 그걸 막는다.
 */
class MafiaChatIntentTest {

    /** 프롬프트를 그대로 모아두는 가짜 런타임(동기 실행). */
    private static final class Capturing extends MafiaBotRuntime {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        Capturing() { super(null); }
        @Override public boolean available() { return true; }
        @Override public void submit(Runnable task) { task.run(); }
        @Override public String chat(String user) { prompts.add(user); return "그건 좀 애매한데"; }
        @Override public String vote(String user) { return "0"; }

        /** 프롬프트 중 조건을 만족하는 첫 개를 찾는다. */
        String find(String... musts) {
            outer:
            for (String p : prompts) {
                for (String m : musts) if (!p.contains(m)) continue outer;
                return p;
            }
            return null;
        }
    }

    /** 토론(DISCUSS)까지 진행시킨다. 밤에는 사람이 지목하고 봇은 스스로 움직인다. */
    private static void driveToDiscuss(MafiaService svc) throws Exception {
        for (int i = 0; i < 200; i++) {
            MafiaStateResponse st = svc.me("host");
            if ("DISCUSS".equals(st.status())) return;
            if ("NIGHT".equals(st.status()) && st.alive() && !st.selectable().isEmpty() && st.myTarget() <= 0)
                svc.nightAction("host", st.selectable().get(0));
            Thread.sleep(100);
        }
        throw new AssertionError("토론까지 진행되지 않았다");
    }

    @Test
    void 첫_발언에는_지목말고_사실정리를_시킨다() throws Exception {
        Capturing bot = new Capturing();
        MafiaService svc = new MafiaService(bot);
        svc.newGame("host", new NewMafiaRequest("방장", 20, 120, null, null, null, null, null));
        svc.addBots(3);
        svc.start("host");
        driveToDiscuss(svc);

        // 첫 봇 발언 프롬프트가 나올 때까지 폴링(예약이 2.5초 뒤부터)
        for (int i = 0; i < 100 && bot.prompts.isEmpty(); i++) { svc.me("host"); Thread.sleep(100); }
        assertThat(bot.prompts).isNotEmpty();

        String first = bot.prompts.get(0);
        assertThat(first).contains("[이번 발언에서 할 일]");
        assertThat(first).contains("남을 지목하지 말고");   // 근거 없는 첫 지목 방지
    }

    @Test
    void 이름이_불리면_해명하라고_지시한다() throws Exception {
        Capturing bot = new Capturing();
        MafiaService svc = new MafiaService(bot);
        svc.newGame("host", new NewMafiaRequest("방장", 20, 120, null, null, null, null, null));
        svc.addBots(3);
        svc.start("host");
        driveToDiscuss(svc);

        for (int i = 0; i < 100 && bot.prompts.isEmpty(); i++) { svc.me("host"); Thread.sleep(100); }
        assertThat(bot.prompts).isNotEmpty();

        // 살아있는 봇 하나를 골라 사람이 대놓고 지목한다 → 그 봇의 다음 프롬프트는 해명 지시여야 한다.
        String target = svc.me("host").players().stream()
                .filter(v -> v.bot() && v.alive()).map(MafiaStateResponse.PlayerView::nick)
                .findFirst().orElseThrow();
        svc.sendChat("host", target + " 너 왜 아까부터 말이 바뀌냐");

        String hit = null;
        for (int i = 0; i < 150 && hit == null; i++) {
            svc.me("host");
            hit = bot.find("너의 이름은 '" + target + "'다", "누가 너를 지목하거나 언급했다");
            Thread.sleep(100);
        }
        assertThat(hit).as("지목당한 봇에게 해명 지시가 들어가야 한다").isNotNull();
    }
}
