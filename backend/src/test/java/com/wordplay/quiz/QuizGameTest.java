package com.wordplay.quiz;

import com.wordplay.quiz.dto.QuizState;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 퀴즈 진행 규칙. AI 없이 내장 문제로 돌린다. */
class QuizGameTest {

    private static QuizBank bank() {
        QuizBank b = new QuizBank(null);
        ReflectionTestUtils.setField(b, "dailyCallLimit", 0);   // 호출 자체를 막아 내장 문제만 쓴다
        return b;
    }

    private static QuizGame solo(int rounds) {
        return new QuizGame("host", "우노", 5, rounds, 20, bank());
    }

    private static QuizGame withPlayers(int n, int rounds) {
        QuizGame g = new QuizGame("host", "우노", 5, rounds, 20, bank());
        for (int i = 1; i < n; i++) g.join("p" + i, "친구" + i);
        return g;
    }

    /** 현재 문제의 정답을 그대로 제출한다(객관식은 보기 번호). */
    private static void answerCorrectly(QuizGame g, String clientId) {
        QuizQuestion q = g.questionsList().get(g.index());
        g.answer(clientId, q.kind() == QuizQuestion.Kind.CHOICE
                ? String.valueOf(q.answerIndex()) : q.answerLabel());
    }

    @Test
    void 혼자서도_시작된다() {
        QuizGame g = solo(5);
        g.start("host");
        assertThat(g.phase()).isEqualTo(QuizGame.Phase.ASKING);
        assertThat(g.questionsList()).hasSize(5);
    }

    @Test
    void 방장만_시작할_수_있다() {
        QuizGame g = withPlayers(2, 5);
        assertThatThrownBy(() -> g.start("p1")).hasMessageContaining("방장만");
    }

    @Test
    void 정답을_맞히면_점수가_오른다() {
        QuizGame g = solo(3);
        g.start("host");
        answerCorrectly(g, "host");
        QuizState st = g.me("host");
        assertThat(st.players().get(0).score()).isGreaterThanOrEqualTo(QuizGame.BASE_SCORE);
        assertThat(st.players().get(0).correct()).isEqualTo(1);
        assertThat(st.myAnswerRight()).isTrue();
    }

    @Test
    void 오답이면_점수가_오르지_않고_연속이_끊긴다() {
        QuizGame g = solo(3);
        g.start("host");
        answerCorrectly(g, "host");
        g.me("host");                                   // REVEAL → 다음 문제로 넘기기 위한 tick
        ReflectionTestUtils.setField(g, "revealAt", 0L);
        g.me("host");

        g.answer("host", "확실히 틀린 답 12345");
        QuizState st = g.me("host");
        assertThat(st.players().get(0).correct()).isEqualTo(1);
        assertThat(st.players().get(0).streak()).isZero();
        assertThat(st.players().get(0).bestStreak()).isEqualTo(1);
    }

    @Test
    void 빨리_맞히면_점수를_더_받는다() {
        QuizGame g = solo(3);
        g.start("host");
        QuizQuestion q = g.questionsList().get(0);
        long dl = g.me("host").deadline();

        int fast = g.scoreFor(q, dl - 19_000);          // 1초 만에
        int slow = g.scoreFor(q, dl - 1_000);           // 19초 만에
        assertThat(fast).isGreaterThan(slow);
        assertThat(slow).isGreaterThanOrEqualTo(QuizGame.BASE_SCORE);
    }

    @Test
    void 주관식은_배점이_더_높다() {
        QuizGame g = solo(3);
        g.start("host");
        QuizQuestion choice = new QuizQuestion("a", "t", 5, QuizQuestion.Kind.CHOICE,
                "q", java.util.List.of("1", "2", "3", "4"), 0, java.util.List.of(), "e");
        QuizQuestion text = new QuizQuestion("b", "t", 5, QuizQuestion.Kind.TEXT,
                "q", java.util.List.of(), -1, java.util.List.of("답"), "e");
        long at = g.me("host").deadline() - 10_000;
        assertThat(g.scoreFor(text, at)).isGreaterThan(g.scoreFor(choice, at));
    }

    @Test
    void 한_문제에_두_번_답할_수_없다() {
        // 혼자면 답하는 즉시 공개로 넘어가므로 이 경로가 안 열린다. 둘 이상일 때를 본다.
        QuizGame g = withPlayers(2, 3);
        g.start("host");
        answerCorrectly(g, "host");
        assertThatThrownBy(() -> g.answer("host", "또 답")).hasMessageContaining("이미 답했습니다");
    }

    @Test
    void 혼자_할_때는_답하는_즉시_정답이_공개된다() {
        // 혼자 심심풀이로 푸는 용도라 매 문제 제한시간을 다 기다리게 하면 지루하다.
        QuizGame g = solo(3);
        g.start("host");
        answerCorrectly(g, "host");
        assertThat(g.phase()).isEqualTo(QuizGame.Phase.REVEAL);
    }

    @Test
    void 전원이_답하면_기다리지_않고_정답을_공개한다() {
        QuizGame g = withPlayers(2, 3);
        g.start("host");
        answerCorrectly(g, "host");
        assertThat(g.phase()).as("아직 한 명이 남았다").isEqualTo(QuizGame.Phase.ASKING);
        answerCorrectly(g, "p1");
        assertThat(g.phase()).isEqualTo(QuizGame.Phase.REVEAL);
    }

    @Test
    void 공개_단계에서만_정답과_남의_답이_보인다() {
        QuizGame g = withPlayers(2, 3);
        g.start("host");
        assertThat(g.me("host").lastReveal()).as("문제 중에는 정답을 주지 않는다").isNull();

        answerCorrectly(g, "host");
        g.answer("p1", "틀린답");
        QuizState.Reveal r = g.me("host").lastReveal();
        assertThat(r).isNotNull();
        assertThat(r.answer()).isNotBlank();
        assertThat(r.rightSeats()).containsExactly(0);
        assertThat(r.given()).containsKeys(0, 1);
    }

    @Test
    void 문제_중에는_남이_무엇을_썼는지_모른다() {
        QuizGame g = withPlayers(2, 3);
        g.start("host");
        answerCorrectly(g, "host");
        QuizState st = g.me("p1");
        assertThat(st.players().get(0).answered()).as("답했다는 사실만").isTrue();
        assertThat(st.lastReveal()).isNull();
    }

    @Test
    void 시간이_지나면_정답이_공개되고_다음_문제로_간다() {
        QuizGame g = solo(3);
        g.start("host");
        ReflectionTestUtils.setField(g, "deadline", System.currentTimeMillis() - 1);
        g.me("host");
        assertThat(g.phase()).isEqualTo(QuizGame.Phase.REVEAL);

        ReflectionTestUtils.setField(g, "revealAt", System.currentTimeMillis() - 1);
        g.me("host");
        assertThat(g.phase()).isEqualTo(QuizGame.Phase.ASKING);
        assertThat(g.index()).isEqualTo(1);
    }

    @Test
    void 마지막_문제가_끝나면_게임이_끝난다() {
        QuizGame g = solo(3);
        g.start("host");
        for (int i = 0; i < 3; i++) {
            answerCorrectly(g, "host");
            ReflectionTestUtils.setField(g, "revealAt", System.currentTimeMillis() - 1);
            g.me("host");
        }
        assertThat(g.phase()).isEqualTo(QuizGame.Phase.ENDED);
        assertThat(g.me("host").players().get(0).correct()).isEqualTo(3);
    }

    @Test
    void 난이도와_문제수는_범위를_벗어나면_잘린다() {
        QuizGame low = new QuizGame("h", "n", 0, 1, 1, bank());
        QuizGame high = new QuizGame("h", "n", 99, 999, 999, bank());
        assertThat(low.me("h").level()).isEqualTo(1);
        assertThat(high.me("h").level()).isEqualTo(10);
        assertThat(low.me("h").questionSec()).isEqualTo(QuizGame.MIN_SEC);
        assertThat(high.me("h").questionSec()).isEqualTo(QuizGame.MAX_SEC);
    }

    @Test
    void 나간_사람을_기다리지_않는다() {
        QuizGame g = withPlayers(2, 3);
        g.start("host");
        answerCorrectly(g, "host");
        g.leave("p1");
        assertThat(g.phase()).as("남은 사람이 다 답했으면 바로 공개").isEqualTo(QuizGame.Phase.REVEAL);
    }

    @Test
    void 시작_전에는_답할_수_없다() {
        QuizGame g = solo(3);
        assertThatThrownBy(() -> g.answer("host", "1")).hasMessageContaining("답할 수 없습니다");
    }
}
