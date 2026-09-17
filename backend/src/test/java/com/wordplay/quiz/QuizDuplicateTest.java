package com.wordplay.quiz;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 문제가 또 나오는 것 막기.
 *
 * <p>"아직 중복 문제가 좀 나온다"는 지적. 지문만 비교하면 표현을 바꾼 같은 문제가 그대로
 * 통과하고, 애초에 모델이 대표 문제부터 만들어대는 것도 막지 못한다. 세 겹으로 막는다:
 * 지문, 정답, 그리고 "이미 낸 문제"를 프롬프트로 알려주기.
 */
class QuizDuplicateTest {

    private static QuizBank bank() {
        QuizBank b = new QuizBank(null);
        ReflectionTestUtils.setField(b, "dailyCallLimit", 200);
        return b;
    }

    private static String one(String question, String answer) {
        return "[{\"kind\":\"TEXT\",\"topic\":\"지리\",\"question\":\"" + question
                + "\",\"answers\":[\"" + answer + "\"],\"explain\":\"해설\"}]";
    }

    @Test
    void 표현만_바꾼_같은_문제를_걸러낸다() {
        QuizBank b = bank();
        assertThat(b.parse(one("세계에서 가장 높은 산은?", "에베레스트"), 5)).hasSize(1);
        assertThat(b.parse(one("지구에서 가장 높은 산의 이름은?", "에베레스트"), 5))
                .as("지문은 다르지만 묻는 사실이 같다").isEmpty();
    }

    @Test
    void 난이도가_달라도_같은_사실은_한_번만() {
        QuizBank b = bank();
        assertThat(b.parse(one("우리나라의 수도는?", "서울"), 3)).hasSize(1);
        assertThat(b.parse(one("대한민국의 수도는 어디인가?", "서울"), 8)).isEmpty();
    }

    @Test
    void 정답이_다르면_통과시킨다() {
        QuizBank b = bank();
        assertThat(b.parse(one("프랑스의 수도는?", "파리"), 5)).hasSize(1);
        assertThat(b.parse(one("일본의 수도는?", "도쿄"), 5)).hasSize(1);
    }

    @Test
    void 오래된_정답까지_막지는_않는다() {
        // 전부 영구히 막으면 낼 문제가 마른다. 최근 것만 기억한다.
        QuizBank b = bank();
        assertThat(b.parse(one("첫 문제?", "서울"), 5)).hasSize(1);
        for (int i = 0; i < QuizBank.ANSWER_MEMORY + 50; i++)
            b.parse(one("채우기 " + i + "?", "답" + i), 5);
        assertThat(b.parse(one("한참 뒤 같은 답을 쓰는 문제?", "서울"), 5))
                .as("오래돼 잊힌 정답은 다시 쓸 수 있어야 한다").hasSize(1);
    }

    @Test
    void 이미_낸_문제를_다음_생성에_알려준다() {
        QuizBank b = bank();
        assertThat(b.avoidBlock(5)).as("낸 게 없으면 넣을 것도 없다").isEmpty();
        b.parse(one("훈민정음을 만든 임금은?", "세종"), 5);

        String block = b.avoidBlock(5);
        assertThat(block).contains("훈민정음을 만든 임금은?");
        assertThat(block).contains("같은 사실을 묻지 마라");
        assertThat(b.avoidBlock(7)).as("난이도가 다르면 그 난이도에서 낸 것만 알려준다").isEmpty();
    }

    @Test
    void 알려주는_목록은_무한히_길어지지_않는다() {
        QuizBank b = bank();
        for (int i = 0; i < QuizBank.RECENT_HINT * 3; i++) b.parse(one("문제 " + i + "?", "답" + i), 5);
        long lines = b.avoidBlock(5).lines().filter(l -> l.startsWith("- ")).count();
        assertThat(lines).isEqualTo(QuizBank.RECENT_HINT);
    }

    @Test
    void 출제_분야가_넉넉하고_겹치지_않는다() {
        // 좁으면 같은 소재가 돌고 돌아 중복처럼 느껴진다. 겹친 항목은 뽑기 확률을 왜곡한다.
        assertThat(QuizBank.TOPICS).hasSizeGreaterThan(40).doesNotHaveDuplicates();
        assertThat(QuizBank.TOPICS_PER_BATCH).isLessThan(QuizBank.TOPICS.size());
    }

    @Test
    void 내장_문제도_서로_겹치지_않는다() {
        // AI가 꺼져 있으면 이것만 돌아간다. 여기에 중복이 있으면 바로 티가 난다.
        List<QuizQuestion> all = QuizFallback.pick(5, 999, List.of());
        assertThat(all).hasSizeGreaterThan(40);
        assertThat(all).extracting(QuizQuestion::id).doesNotHaveDuplicates();
        assertThat(all)
                .extracting(q -> QuizQuestion.normalize(q.answerLabel()))
                .filteredOn(a -> !QuizBank.genericAnswer(a))   // "8개"는 행성 수이기도, 거미 다리 수이기도 하다
                .doesNotHaveDuplicates();
    }

    @Test
    void 숫자_답은_정답_대조에서_뺀다() {
        assertThat(QuizBank.genericAnswer("8개")).isTrue();
        assertThat(QuizBank.genericAnswer("12")).isTrue();
        assertThat(QuizBank.genericAnswer("에베레스트")).isFalse();
        assertThat(QuizBank.genericAnswer("42195km")).isFalse();

        QuizBank b = bank();
        assertThat(b.parse(one("태양계의 행성은 몇 개인가?", "8개"), 5)).hasSize(1);
        assertThat(b.parse(one("거미의 다리는 몇 개인가?", "8개"), 5))
                .as("상관없는 문제까지 걸리면 안 된다").hasSize(1);
    }

    @Test
    void 시스템_프롬프트가_중복_출제를_금지한다() {
        assertThat(QuizBank.SYSTEM).contains("중복 금지");
        assertThat(QuizBank.SYSTEM).contains("정답이 겹치는 문제도 두 개 내지 마라");
    }
}
