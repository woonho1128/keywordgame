package com.wordplay.quiz;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문제 수를 정하는 모드에서, 시작할 때 다 받지 않고도 끝까지 문제가 나오는지.
 *
 * <p>시작 요청이 막히지 않게 한 배치만 미리 받도록 바꿨다. 나머지는 푸는 동안 채우는데,
 * 이게 안 되면 10번째 문제에서 판이 그냥 끝나버린다.
 */
class QuizClassicTopUpTest {

    private static QuizGame game(int rounds) {
        QuizBank b = new QuizBank(null);
        ReflectionTestUtils.setField(b, "dailyCallLimit", 0);   // 내장 문제만으로 굴린다
        return new QuizGame("host", "우노", 5, rounds, 20, null, null, b, null);
    }

    /** 한 문제를 시간 초과로 흘려보낸다(정답 공개 → 다음 문제). */
    private static void skipRound(QuizGame g) {
        g.expireForTest();
        g.tick();                       // ASKING → REVEAL
        g.expireForTest();
        g.tick();                       // REVEAL → 다음 문제
    }

    @Test
    void 시작할_때는_한_배치만_받는다() {
        QuizGame g = game(30);
        g.start("host");
        assertThat(g.questionsList()).hasSizeLessThanOrEqualTo(QuizBank.BATCH);
        assertThat(g.me("host").totalRounds()).as("화면에는 정한 문제 수가 그대로 보인다").isEqualTo(30);
    }

    @Test
    void 정한_문제_수만큼_끝까지_진행된다() {
        QuizGame g = game(25);
        g.start("host");
        for (int i = 0; i < 24; i++) {
            assertThat(g.phase()).as(i + "번째 문제에서 판이 끊겼다").isEqualTo(QuizGame.Phase.ASKING);
            assertThat(g.me("host").question()).as(i + "번째 문제가 비었다").isNotNull();
            skipRound(g);
        }
        assertThat(g.index()).isEqualTo(24);
        skipRound(g);
        assertThat(g.phase()).as("마지막 문제까지 풀면 끝난다").isEqualTo(QuizGame.Phase.ENDED);
    }

    @Test
    void 이어붙인_문제도_서로_겹치지_않는다() {
        QuizGame g = game(25);
        g.start("host");
        for (int i = 0; i < 24; i++) skipRound(g);
        assertThat(g.questionsList()).extracting(QuizQuestion::id).doesNotHaveDuplicates();
    }
}
