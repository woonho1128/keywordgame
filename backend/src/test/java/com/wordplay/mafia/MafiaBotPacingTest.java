package com.wordplay.mafia;

import com.wordplay.mafia.ai.MafiaBotRuntime;
import com.wordplay.mafia.dto.MafiaStateResponse;
import com.wordplay.mafia.dto.NewMafiaRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 봇 발언 간격 처리.
 *
 * <p>봇 수를 5명까지 늘리면서 "동시에 응답이 도착해 간격에 걸리는" 일이 잦아졌다.
 * 예전에는 걸린 발언을 그냥 버리고 나중에 LLM을 다시 불렀다(= 호출 낭비 + 대화 끊김).
 * 이제는 들고 있다가 간격이 지나면 그대로 내보낸다.
 */
class MafiaBotPacingTest {

    private static MafiaBotRuntime countingRuntime(AtomicInteger calls) {
        return new MafiaBotRuntime(null) {
            @Override public boolean available() { return true; }
            @Override public void submit(Runnable task) { task.run(); }
            @Override public String chat(String user) { calls.incrementAndGet(); return "새로 물어본 말"; }
            @Override public String vote(String user) { return "0"; }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Long> chatAt(MafiaService svc) {
        return (Map<Integer, Long>) ReflectionTestUtils.getField(svc, "botChatAt");
    }

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
    void 간격에_걸린_발언은_버리지_않고_나중에_그대로_내보낸다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MafiaService svc = new MafiaService(countingRuntime(calls));
        svc.newGame("host", new NewMafiaRequest("방장", 20, 200, null, null, null, null, null));
        svc.addBots(4);
        svc.start("host");
        driveToDiscuss(svc);

        // 살아있는 봇 두 명(0-based 좌석)의 응답이 거의 동시에 도착한 상황을 만든다.
        List<Integer> seats = svc.me("host").players().stream()
                .filter(v -> v.bot() && v.alive()).map(v -> v.seat() - 1).toList();
        assertThat(seats).hasSizeGreaterThanOrEqualTo(2);
        int a = seats.get(0), b = seats.get(1);

        long round = ((Number) ReflectionTestUtils.getField(svc, "round")).longValue();
        svc.applyBotChat(a, round, "봇1 첫마디");
        svc.applyBotChat(b, round, "봇2 첫마디");

        // 두 번째는 간격에 걸려 아직 안 나온다.
        assertThat(texts(svc)).contains("봇1 첫마디").doesNotContain("봇2 첫마디");

        // 간격이 지나면 새로 묻지 않고 들고 있던 말이 나온다.
        int before = calls.get();
        chatAt(svc).put(b, 0L);
        ReflectionTestUtils.setField(svc, "lastBotChatMs", 0L);
        svc.me("host");   // tick

        assertThat(texts(svc)).contains("봇2 첫마디");
        assertThat(calls.get()).as("보관해둔 발언을 쓰므로 추가 LLM 호출은 없다").isEqualTo(before);
    }

    private static String texts(MafiaService svc) {
        return svc.me("host").chat().stream().map(MafiaStateResponse.ChatView::text)
                .reduce("", (a, b) -> a + "|" + b);
    }
}
