package com.wordplay.mafia.ai;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추론 모델과 일반 모델은 요청 규격이 달라, 모델명에 따라 분기가 제대로 되는지 확인한다.
 * (분기가 틀리면 400 에러가 나거나 추론 토큰이 답변 예산을 먹어 빈 응답이 온다.)
 */
class OpenAiChatClientTest {

    private OpenAiChatClient client(String model, String mode) {
        OpenAiChatClient c = new OpenAiChatClient();
        ReflectionTestUtils.setField(c, "model", model);
        ReflectionTestUtils.setField(c, "reasoningMode", mode);
        return c;
    }

    @Test
    void gpt5_계열과_o시리즈는_추론_모델로_판단한다() {
        assertThat(client("gpt-5.6-luna", "auto").reasoningModel()).isTrue();
        assertThat(client("gpt-5", "auto").reasoningModel()).isTrue();
        assertThat(client("o3-mini", "auto").reasoningModel()).isTrue();
        assertThat(client("o4-mini", "auto").reasoningModel()).isTrue();
    }

    @Test
    void gpt4_계열은_일반_모델로_판단한다() {
        assertThat(client("gpt-4o", "auto").reasoningModel()).isFalse();
        assertThat(client("gpt-4o-mini", "auto").reasoningModel()).isFalse();
        assertThat(client("gpt-4.1", "auto").reasoningModel()).isFalse();
    }

    /** 새 모델명이 나와도 재배포 없이 환경변수로 규격을 강제할 수 있어야 한다. */
    @Test
    void 환경변수로_강제할_수_있다() {
        assertThat(client("gpt-4o", "true").reasoningModel()).isTrue();
        assertThat(client("gpt-5.6-luna", "false").reasoningModel()).isFalse();
    }

    /** 허용되지 않는 값을 보내면 400이 나서 봇이 통째로 침묵한다(실제로 'minimal'로 겪었다). */
    @Test
    void 허용된_노력_수준만_보낸다() {
        for (String ok : new String[]{"none", "low", "medium", "high", "xhigh", "max"}) {
            OpenAiChatClient c = client("gpt-5.6-luna", "auto");
            ReflectionTestUtils.setField(c, "reasoningEffort", ok);
            assertThat(c.effortOrNull()).isEqualTo(ok);
        }
        // 대소문자·공백은 정규화
        OpenAiChatClient c = client("gpt-5.6-luna", "auto");
        ReflectionTestUtils.setField(c, "reasoningEffort", "  HIGH ");
        assertThat(c.effortOrNull()).isEqualTo("high");
    }

    @Test
    void 모르는_노력_수준은_파라미터를_생략한다() {
        for (String bad : new String[]{"minimal", "off", "", "  ", "ultra"}) {
            OpenAiChatClient c = client("gpt-5.6-luna", "auto");
            ReflectionTestUtils.setField(c, "reasoningEffort", bad);
            assertThat(c.effortOrNull()).as("'%s' 는 생략돼야 함", bad).isNull();
        }
    }

    /** 발언과 투표는 필요한 사고량이 달라 호출별로 다른 값을 줄 수 있어야 한다. */
    @Test
    void 호출별로_노력_수준을_다르게_줄_수_있다() {
        OpenAiChatClient c = client("gpt-5.6-luna", "auto");
        ReflectionTestUtils.setField(c, "reasoningEffort", "none");
        assertThat(c.normEffort("medium")).isEqualTo("medium");   // 이 호출만 다르게
        assertThat(c.effortOrNull()).isEqualTo("none");           // 기본값은 그대로
        assertThat(c.normEffort("minimal")).isNull();             // 잘못된 값은 생략
    }

    @Test
    void 키가_없으면_호출하지_않는다() {
        OpenAiChatClient c = client("gpt-5.6-luna", "auto");
        ReflectionTestUtils.setField(c, "apiKey", "");
        assertThat(c.isConfigured()).isFalse();
        assertThat(c.complete("sys", "user", 80, 0.9)).isNull();
    }
}
