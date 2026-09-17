package com.wordplay.mafia;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 봇 발언 정리 규칙.
 * 실제 판에서 "음…"으로 매 줄이 시작하고, 모델이 다른 문자체계를 섞어 뱉는 문제가 있었다.
 */
class MafiaChatCleanTest {

    private String clean(String s) {
        return (String) ReflectionTestUtils.invokeMethod(MafiaService.class, "cleanBotChat", s);
    }

    @Test
    void 앞머리_군말을_없앤다() {
        assertThat(clean("음… test 명의라는 말만으론 애매한데")).isEqualTo("test 명의라는 말만으론 애매한데");
        assertThat(clean("흠.. 봇2가 급했지")).isEqualTo("봇2가 급했지");
        assertThat(clean("아, 그건 좀 이상한데")).isEqualTo("그건 좀 이상한데");
    }

    @Test
    void 한국어가_아닌_문자체계는_지운다() {
        // 실제로 "신경 쓰여ેણ" 처럼 구자라트 문자가 섞여 나왔다.
        assertThat(clean("봇3이 편드는 흐름도 좀 신경 쓰여ેણ")).isEqualTo("봇3이 편드는 흐름도 좀 신경 쓰여");
        assertThat(clean("这个 봇1 수상함")).isEqualTo("봇1 수상함");
    }

    @Test
    void 정상_발언은_그대로_둔다() {
        assertThat(clean("ㅋㅋ 왜 갑자기 나야 근거가 뭔데")).isEqualTo("ㅋㅋ 왜 갑자기 나야 근거가 뭔데");
        assertThat(clean("일단 오늘은 봇2 가는 게 맞는 듯")).isEqualTo("일단 오늘은 봇2 가는 게 맞는 듯");
    }

    @Test
    void 따옴표와_공백을_정리하고_길이를_제한한다() {
        assertThat(clean("  \"봇1 수상함\"  ")).isEqualTo("봇1 수상함");
        assertThat(clean("가".repeat(200))).hasSize(120);
    }

    @Test
    void 군말처럼_보이는_평범한_단어는_건드리지_않는다() {
        // "어제", "아니", "아까"의 첫 글자를 군말로 오인해 깎아먹던 버그.
        assertThat(clean("어제 봄이 찍었잖아")).isEqualTo("어제 봄이 찍었잖아");
        assertThat(clean("아니 근데 그건 좀")).isEqualTo("아니 근데 그건 좀");
        assertThat(clean("아까부터 말이 바뀌는데")).isEqualTo("아까부터 말이 바뀌는데");
        assertThat(clean("음식 얘기 왜 나와")).isEqualTo("음식 얘기 왜 나와");
    }

    @Test
    void 사람_발언은_군말을_깎지_않는다() {
        String humanCleaned = (String) ReflectionTestUtils.invokeMethod(
                MafiaService.class, "cleanChat", "음... 잘 모르겠는데");
        assertThat(humanCleaned).isEqualTo("음... 잘 모르겠는데");
    }

    @Test
    void 널이나_빈값은_빈_문자열() {
        assertThat(clean(null)).isEmpty();
        assertThat(clean("   ")).isEmpty();
        assertThat(clean("음…")).isEmpty();   // 군말만 있으면 남는 게 없다
    }
}
