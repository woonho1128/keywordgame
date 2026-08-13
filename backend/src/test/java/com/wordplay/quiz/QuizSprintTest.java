package com.wordplay.quiz;

import com.wordplay.quiz.dto.QuizState;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 무제한(배틀) 모드.
 *
 * <p>제한시간 동안 각자 자기 속도로 최대한 많이 푼다. 문제 목록은 공유하고 커서만
 * 사람마다 따로 두어, 모두 같은 문제를 같은 순서로 받으면서도 필요한 문제 수는
 * "가장 많이 푼 사람" 만큼으로 유지된다.
 */
class QuizSprintTest {

    private static QuizBank bank() {
        QuizBank b = new QuizBank(null);
        ReflectionTestUtils.setField(b, "dailyCallLimit", 0);   // AI를 부르지 않고 내장 문제로
        return b;
    }

    private static QuizGame sprint(int limitSec, int players) {
        QuizGame g = new QuizGame("host", "우노", 3, null, null, "SPRINT", limitSec, bank());
        for (int i = 1; i < players; i++) g.join("p" + i, "친구" + i);
        return g;
    }

    /** 지금 내 문제의 정답을 낸다. */
    private static void correct(QuizGame g, String cid) {
        QuizQuestion q = g.questionsList().get(g.byClient(cid).cursor);
        g.answer(cid, q.kind() == QuizQuestion.Kind.CHOICE
                ? String.valueOf(q.answerIndex()) : q.answerLabel());
    }

    @Test
    void 모드와_제한시간이_상태에_실린다() {
        QuizGame g = sprint(60, 1);
        g.start("host");
        QuizState st = g.me("host");
        assertThat(st.mode()).isEqualTo("SPRINT");
        assertThat(st.limitSec()).isEqualTo(60);
        assertThat(st.totalRounds()).as("무제한이므로 전체 문제 수는 없다").isZero();
        assertThat(st.phase()).isEqualTo("ASKING");
    }

    @Test
    void 답하면_정답공개를_기다리지_않고_다음_문제가_나온다() {
        QuizGame g = sprint(60, 1);
        g.start("host");
        String first = g.me("host").question();
        correct(g, "host");
        QuizState st = g.me("host");
        assertThat(st.phase()).as("REVEAL로 멈추지 않는다").isEqualTo("ASKING");
        assertThat(st.question()).isNotEqualTo(first);
        assertThat(st.canAnswer()).isTrue();
        assertThat(st.round()).isEqualTo(2);
    }

    @Test
    void 직전_결과를_짧게_알려준다() {
        QuizGame g = sprint(60, 1);
        g.start("host");
        correct(g, "host");
        QuizState.MyLast last = g.me("host").myLast();
        assertThat(last).isNotNull();
        assertThat(last.right()).isTrue();
        assertThat(last.answer()).isNotBlank();
    }

    @Test
    void 맞힌_개수와_오답이_따로_집계된다() {
        QuizGame g = sprint(60, 1);
        g.start("host");
        correct(g, "host");
        g.answer("host", "절대 아닌 답 99999");
        QuizState.PlayerView me = g.me("host").players().get(0);
        assertThat(me.correct()).isEqualTo(1);
        assertThat(me.wrong()).isEqualTo(1);
        assertThat(me.solved()).isEqualTo(2);
    }

    @Test
    void 모르는_문제는_넘길_수_있다() {
        QuizGame g = sprint(60, 1);
        g.start("host");
        String before = g.me("host").question();
        g.skip("host");
        QuizState st = g.me("host");
        assertThat(st.question()).isNotEqualTo(before);
        assertThat(st.players().get(0).skipped()).isEqualTo(1);
        assertThat(st.myLast().right()).as("넘긴 것은 정답도 오답도 아니다").isNull();
    }

    @Test
    void 고전_모드에서는_넘길_수_없다() {
        QuizGame g = new QuizGame("host", "우노", 3, 5, 20, "CLASSIC", null, bank());
        g.start("host");
        assertThatThrownBy(() -> g.skip("host")).hasMessageContaining("넘길 수 없습니다");
    }

    @Test
    void 각자_자기_속도로_달린다() {
        QuizGame g = sprint(60, 2);
        g.start("host");
        // 방장만 세 문제를 푼다. 친구는 아직 첫 문제.
        for (int i = 0; i < 3; i++) correct(g, "host");
        assertThat(g.byClient("host").cursor).isEqualTo(3);
        assertThat(g.byClient("p1").cursor).isZero();
        // 서로 다른 문제를 보고 있다
        assertThat(g.me("host").question()).isNotEqualTo(g.me("p1").question());
    }

    @Test
    void 모두_같은_문제를_같은_순서로_받는다() {
        QuizGame g = sprint(60, 2);
        g.start("host");
        String hostFirst = g.me("host").question();
        String friendFirst = g.me("p1").question();
        assertThat(friendFirst).as("첫 문제는 같다").isEqualTo(hostFirst);
        correct(g, "host");
        String hostSecond = g.me("host").question();
        correct(g, "p1");
        assertThat(g.me("p1").question()).as("두 번째도 같다").isEqualTo(hostSecond);
    }

    @Test
    void 문제가_바닥나도_화면이_비지_않는다() {
        // AI가 없으면 쓸 수 있는 문제가 유한하다. 그래도 제한시간 동안 계속 문제가
        // 나와야 한다 — 새로 못 구하면 이미 낸 문제를 섞어 다시 낸다.
        QuizGame g = sprint(60, 1);
        g.start("host");
        int initial = g.questionsList().size();
        for (int i = 0; i < initial * 2 + 10; i++) {
            assertThat(g.me("host").question()).as(i + "번째 문제").isNotNull();
            g.skip("host");
        }
        assertThat(g.questionsList().size()).as("목록이 늘어난다").isGreaterThan(initial);
    }

    @Test
    void 제한시간이_지나면_끝난다() {
        QuizGame g = sprint(60, 1);
        g.start("host");
        ReflectionTestUtils.setField(g, "deadline", System.currentTimeMillis() - 1);
        g.me("host");
        assertThat(g.phase()).isEqualTo(QuizGame.Phase.ENDED);
    }

    @Test
    void 순위는_맞힌_개수로_정하고_동점이면_오답이_적은_쪽() {
        QuizGame g = sprint(60, 2);
        g.start("host");
        // 방장: 정답 2 · 오답 1 / 친구: 정답 2 · 오답 0 → 친구가 1위
        correct(g, "host"); correct(g, "host"); g.answer("host", "틀린답 xyz");
        correct(g, "p1"); correct(g, "p1");
        QuizState st = g.me("host");
        assertThat(st.players().get(0).name()).isEqualTo("친구1");
        assertThat(st.players().get(1).name()).isEqualTo("우노");
    }

    @Test
    void 미리_받아두는_문제_수는_제한시간에_맞춘다() {
        // 진행 중 생성으로 게임이 멈추지 않게 넉넉히 준비한다.
        assertThat(QuizGame.preloadFor(30)).isGreaterThanOrEqualTo(20);
        assertThat(QuizGame.preloadFor(120)).isGreaterThanOrEqualTo(40);
        assertThat(QuizGame.preloadFor(600)).isLessThanOrEqualTo(80);   // 상한
    }
}
