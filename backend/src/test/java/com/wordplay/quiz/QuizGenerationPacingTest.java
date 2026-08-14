package com.wordplay.quiz;

import com.wordplay.mafia.ai.OpenAiChatClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문제 생성이 요청을 붙잡지 않는지.
 *
 * <p>퀴즈를 시작하면 종종 "Internal Server Error"가 뜬다는 제보가 있었다. 백엔드는 오류도
 * JSON으로 주므로 그건 백엔드가 아니라 <b>앞단 프록시가 먼저 끊은 것</b>이었다. 원인은
 * 시작 요청 안에서 AI 호출을 이어서 여러 번 한 것 — 한 번에 열 문제씩이라 40문제를
 * 준비하려면 20초짜리 호출이 네 번 이어졌다.
 *
 * <p>그래서 여기서 재는 것은 "몇 문제를 받았나"가 아니라 <b>요청 스레드에서 AI를 몇 번
 * 불렀나</b>이다. 배경 스레드는 얼마든지 불러도 사람을 기다리게 하지 않는다.
 */
class QuizGenerationPacingTest {

    /** 호출을 세는 가짜 클라이언트. 부른 스레드가 요청 스레드인지 배경인지 구분한다. */
    private static final class CountingClient extends OpenAiChatClient {
        CountingClient() { super(null); }

        final AtomicInteger onCaller = new AtomicInteger();
        final AtomicInteger total = new AtomicInteger();
        final String caller = Thread.currentThread().getName();

        @Override public boolean isConfigured() { return true; }

        @Override
        public String complete(String system, String user, int maxTokens, double temperature,
                               String effortOverride, String kind, int timeoutOverrideMs) {
            total.incrementAndGet();
            if (caller.equals(Thread.currentThread().getName())) onCaller.incrementAndGet();
            return batch(total.get());
        }

        /** 매번 다른 지문·정답으로 열 문제. 중복 걸러내기에 막히지 않게 한다. */
        private static String batch(int n) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < QuizBank.BATCH; i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"kind\":\"TEXT\",\"topic\":\"과학\",\"question\":\"문제 ")
                        .append(n).append('-').append(i)
                        .append("?\",\"answers\":[\"답").append(n).append('-').append(i)
                        .append("\"],\"explain\":\"해설\"}");
            }
            return sb.append(']').toString();
        }
    }

    private static QuizBank bank(CountingClient c) {
        QuizBank b = new QuizBank(c);
        ReflectionTestUtils.setField(b, "dailyCallLimit", 200);
        return b;
    }

    @Test
    void 많이_달라고_해도_요청_스레드에서는_한_번만_부른다() {
        CountingClient c = new CountingClient();
        List<QuizQuestion> qs = bank(c).take(5, 40);

        assertThat(c.onCaller.get()).as("이어서 부르면 시작 요청이 분 단위로 막힌다").isEqualTo(1);
        assertThat(qs).as("모자란 몫은 내장 문제로 채워 판은 그대로 열린다").hasSize(40);
    }

    @Test
    void 창고에_있으면_아예_부르지_않는다() {
        CountingClient c = new CountingClient();
        QuizBank b = bank(c);
        b.take(5, 3);                        // 한 번 만들어 쓰고 남은 것이 창고에 쌓인다
        int before = c.onCaller.get();
        b.take(5, 3);
        assertThat(c.onCaller.get()).as("창고에서 꺼내 쓰면 호출이 없다").isEqualTo(before);
    }

    @Test
    void 무제한_모드도_시작할_때_한_번만_기다린다() {
        CountingClient c = new CountingClient();
        // 제한시간 10분 → 예전에는 80문제를 미리 받느라 호출이 여덟 번 이어졌다.
        QuizGame g = new QuizGame("host", "우노", 5, 10, 20, "SPRINT", 600, bank(c), null);
        g.start("host");

        assertThat(c.onCaller.get()).isEqualTo(1);
        assertThat(g.phase()).isEqualTo(QuizGame.Phase.ASKING);
        assertThat(g.questionsList()).as("시작에 필요한 만큼은 있어야 한다").isNotEmpty();
    }

    @Test
    void 문제_수를_많이_잡아도_시작은_한_번만_기다린다() {
        CountingClient c = new CountingClient();
        QuizGame g = new QuizGame("host", "우노", 5, 30, 20, null, null, bank(c), null);
        g.start("host");

        assertThat(c.onCaller.get()).isEqualTo(1);
        assertThat(g.me("host").totalRounds())
                .as("나머지는 푸는 동안 채우지만 화면에는 처음부터 30문제로 보여야 한다").isEqualTo(30);
    }
}
