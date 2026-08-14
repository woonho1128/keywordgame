package com.wordplay.quiz;

import com.wordplay.quiz.dto.QuizState;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시간 배틀 배점.
 *
 * <p>맞힌 개수만 세면 4지선다를 아무거나 찍는 게 최적 전략이 된다(1초에 25% 확률로 +1).
 * 틀리면 깎아야 아는 문제를 고르는 경기가 된다. 여기서 지키는 것은 숫자 자체가 아니라
 * <b>찍기가 손해</b>이고 <b>넘기기 연타도 손해</b>라는 관계다.
 */
class QuizSprintScoreTest {

    private static QuizGame battle() {
        QuizBank b = new QuizBank(null);
        ReflectionTestUtils.setField(b, "dailyCallLimit", 0);   // 내장 문제로만 돌린다
        QuizGame g = new QuizGame("host", "우노", 5, 10, 20, "SPRINT", 120, b, null);
        g.start("host");
        return g;
    }

    private static QuizQuestion cur(QuizGame g) {
        return g.questionsList().get(g.byClient("host").cursor);
    }

    private static void answerRight(QuizGame g) {
        QuizQuestion q = cur(g);
        g.answer("host", q.kind() == QuizQuestion.Kind.CHOICE
                ? String.valueOf(q.answerIndex()) : q.answerLabel());
    }

    private static void answerWrong(QuizGame g) {
        QuizQuestion q = cur(g);
        g.answer("host", q.kind() == QuizQuestion.Kind.CHOICE
                ? String.valueOf((q.answerIndex() + 1) % 4) : "확실히 틀린 답");
    }

    private static int score(QuizGame g) { return g.byClient("host").score; }

    @Test
    void 맞히면_오르고_틀리면_깎이고_넘기면_조금_깎인다() {
        QuizGame g = battle();
        int before = score(g);
        QuizQuestion q = cur(g);
        answerRight(g);
        assertThat(score(g) - before).isEqualTo(
                q.kind() == QuizQuestion.Kind.TEXT ? QuizGame.SPRINT_TEXT_RIGHT : QuizGame.SPRINT_RIGHT);

        before = score(g);
        answerWrong(g);
        assertThat(score(g) - before).isEqualTo(QuizGame.SPRINT_WRONG);

        before = score(g);
        g.skip("host");
        assertThat(score(g) - before).isEqualTo(QuizGame.SPRINT_SKIP);
    }

    @Test
    void 찍기는_기대값으로_손해다() {
        // 4지선다를 무작위로 찍으면 4번에 1번 맞는다. 그 한 판이 손해여야 연타가 막힌다.
        int guessing = QuizGame.SPRINT_RIGHT + 3 * QuizGame.SPRINT_WRONG;
        assertThat(guessing).as("네 번 찍어 한 번 맞히면 점수가 줄어야 한다").isNegative();
    }

    @Test
    void 넘기기_연타도_손해다() {
        // 공짜면 쉬운 문제가 나올 때까지 넘기는 게 최적 전략이 된다.
        assertThat(QuizGame.SPRINT_SKIP).isNegative();
        // 다만 주관식을 아예 모를 때는 찍는 것보다 나아야 버튼에 쓸모가 있다.
        assertThat(QuizGame.SPRINT_SKIP).isGreaterThan(QuizGame.SPRINT_WRONG);
    }

    @Test
    void 주관식이_객관식보다_점수가_높다() {
        // 보기가 없어 훨씬 어렵고 표기 차이로 억울하게 틀릴 위험까지 있다.
        assertThat(QuizGame.SPRINT_TEXT_RIGHT).isGreaterThan(QuizGame.SPRINT_RIGHT);
    }

    @Test
    void 점수는_음수까지_내려간다() {
        // 0에서 막으면 0점인 사람에게는 오답이 공짜가 되어 벌점이 사라진다.
        QuizGame g = battle();
        for (int i = 0; i < 5; i++) answerWrong(g);
        assertThat(score(g)).isNegative();
    }

    @Test
    void 순위는_점수로_매긴다() {
        QuizBank b = new QuizBank(null);
        ReflectionTestUtils.setField(b, "dailyCallLimit", 0);
        QuizGame g = new QuizGame("host", "우노", 5, 10, 20, "SPRINT", 120, b, null);
        g.join("p1", "폴짝");
        g.start("host");

        // 우노: 정답 1 + 오답 2 → 마이너스. 폴짝: 정답 1만.
        answerRight(g);
        answerWrong(g);
        answerWrong(g);
        QuizQuestion q = g.questionsList().get(g.byClient("p1").cursor);
        g.answer("p1", q.kind() == QuizQuestion.Kind.CHOICE
                ? String.valueOf(q.answerIndex()) : q.answerLabel());

        QuizState st = g.me("host");
        assertThat(st.players().get(0).name()).as("점수가 높은 쪽이 위").isEqualTo("폴짝");
        assertThat(st.players().get(0).score()).isGreaterThan(st.players().get(1).score());
    }

    @Test
    void 직전_결과에_오르내린_점수를_함께_준다() {
        QuizGame g = battle();
        g.skip("host");
        assertThat(g.me("host").myLast().delta()).isEqualTo(QuizGame.SPRINT_SKIP);
        answerWrong(g);
        assertThat(g.me("host").myLast().delta()).isEqualTo(QuizGame.SPRINT_WRONG);
    }

    @Test
    void 점수와_표시용_증감은_어긋나지_않는다() {
        QuizGame g = battle();
        int expected = 0;
        for (int i = 0; i < 8; i++) {
            if (i % 3 == 0) g.skip("host");
            else if (i % 3 == 1) answerRight(g);
            else answerWrong(g);
            expected += g.me("host").myLast().delta();
            assertThat(score(g)).isEqualTo(expected);
        }
    }
}
