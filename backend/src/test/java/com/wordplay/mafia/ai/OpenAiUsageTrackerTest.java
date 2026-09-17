package com.wordplay.mafia.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 사용량 집계·어림비용 계산. 모델 비교와 추론 토큰 확인에 쓰이므로 수치가 맞아야 한다. */
class OpenAiUsageTrackerTest {

    private OpenAiUsageTracker t;

    @BeforeEach
    void setUp() {
        t = new OpenAiUsageTracker();
        ReflectionTestUtils.setField(t, "priceInput", 0.20);        // gpt-5.6-luna 기준
        ReflectionTestUtils.setField(t, "priceCachedInput", 0.02);
        ReflectionTestUtils.setField(t, "priceOutput", 1.20);
        ReflectionTestUtils.setField(t, "krwPerUsd", 1400.0);
    }

    @Test
    void 캐시된_입력은_싼_단가로_계산한다() {
        // 입력 2000(그중 캐시 1300), 출력 100
        double usd = t.costUsd(2000, 1300, 100);
        double expected = 700 / 1e6 * 0.20 + 1300 / 1e6 * 0.02 + 100 / 1e6 * 1.20;
        assertThat(usd).isCloseTo(expected, within(1e-9));
    }

    @Test
    void 추론_토큰은_출력에_포함되어_과금된다() {
        // 눈에 보이는 답변 50 + 숨은 추론 400 = completion_tokens 450 로 들어온다
        double noReason = t.costUsd(2000, 1300, 50);
        double withReason = t.costUsd(2000, 1300, 450);
        assertThat(withReason).isGreaterThan(noReason);
        assertThat(withReason - noReason).isCloseTo(400 / 1e6 * 1.20, within(1e-9));
    }

    @Test
    void 누적이_쌓이고_요약에_드러난다() {
        t.record("발언", "gpt-5.6-luna", 2000, 1300, 450, 400);
        t.record("투표", "gpt-5.6-luna", 1900, 1300, 320, 300);
        String s = t.summary();
        assertThat(s).contains("호출 2회");
        assertThat(s).contains("추론 700");     // 400 + 300
        assertThat(t.totalUsd()).isGreaterThan(0);
    }

    @Test
    void 호출이_없으면_없다고_알려준다() {
        assertThat(t.summary()).contains("호출 없음");
    }
}
