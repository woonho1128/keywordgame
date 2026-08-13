package com.wordplay.quiz;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정답 판정과 초성 힌트.
 *
 * <p>주관식의 가장 큰 불만은 "맞는 답인데 오답 처리"다. 띄어쓰기·문장부호·괄호 차이는
 * 전부 무시해야 한다.
 */
class QuizQuestionTest {

    private static QuizQuestion text(String... answers) {
        return new QuizQuestion("id", "한국사", 5, QuizQuestion.Kind.TEXT,
                "임진왜란의 명장은?", List.of(), -1, List.of(answers), "해설");
    }

    private static QuizQuestion choice(int idx) {
        return new QuizQuestion("id", "나라·수도", 3, QuizQuestion.Kind.CHOICE,
                "대한민국의 수도는?", List.of("부산", "서울", "인천", "대구"), idx, List.of(), "해설");
    }

    @Test
    void 주관식은_띄어쓰기와_문장부호를_무시한다() {
        QuizQuestion q = text("이순신");
        assertThat(q.accepts("이순신")).isTrue();
        assertThat(q.accepts(" 이순신 ")).isTrue();
        assertThat(q.accepts("이 순신")).isTrue();
        assertThat(q.accepts("이순신.")).isTrue();
        assertThat(q.accepts("이순신!")).isTrue();
    }

    @Test
    void 허용_표기가_여러_개면_모두_인정한다() {
        QuizQuestion q = text("이순신", "충무공 이순신", "충무공");
        assertThat(q.accepts("충무공")).isTrue();
        assertThat(q.accepts("충무공이순신")).isTrue();
        assertThat(q.accepts("이순신")).isTrue();
    }

    @Test
    void 영문_답은_대소문자를_무시한다() {
        QuizQuestion q = text("DNA", "디엔에이");
        assertThat(q.accepts("dna")).isTrue();
        assertThat(q.accepts("DNA")).isTrue();
        assertThat(q.accepts("디엔에이")).isTrue();
    }

    @Test
    void 괄호_설명은_비교에서_제외한다() {
        QuizQuestion q = text("아마존강(Amazon)");
        assertThat(q.accepts("아마존강")).isTrue();
    }

    @Test
    void 틀린_답과_빈_답은_오답이다() {
        QuizQuestion q = text("이순신");
        assertThat(q.accepts("권율")).isFalse();
        assertThat(q.accepts("")).isFalse();
        assertThat(q.accepts("   ")).isFalse();
        assertThat(q.accepts(null)).isFalse();
    }

    @Test
    void 객관식은_정답_보기만_인정한다() {
        QuizQuestion q = choice(1);
        assertThat(q.accepts("서울")).isTrue();
        assertThat(q.accepts("부산")).isFalse();
        assertThat(q.answerLabel()).isEqualTo("서울");
    }

    @Test
    void 초성_힌트는_겹자모를_그대로_보여준다() {
        assertThat(QuizQuestion.chosung("이순신")).isEqualTo("ㅇㅅㅅ");
        assertThat(QuizQuestion.chosung("까마귀")).isEqualTo("ㄲㅁㄱ");   // ㄱㄱ이 아니다
        assertThat(QuizQuestion.chosung("태평양")).isEqualTo("ㅌㅍㅇ");
        assertThat(QuizQuestion.chosung("아마존강")).isEqualTo("ㅇㅁㅈㄱ");
    }

    @Test
    void 영문_숫자_정답은_힌트에_노출되지_않는다() {
        // 예전에는 비한글을 그대로 통과시켜 "DNA"가 힌트로 그대로 나갔다(정답 노출).
        assertThat(text("DNA").hint()).isEqualTo("___");
        assertThat(text("H2O").hint()).isEqualTo("___");
        assertThat(text("아이폰 15").hint()).isEqualTo("ㅇㅇㅍ __");
        // 가려도 정답과 같아지면 힌트를 주지 않는다
        assertThat(text("8").hint()).isEqualTo("_");
    }

    @Test
    void 힌트는_어떤_정답에도_정답을_그대로_담지_않는다() {
        for (String a : new String[]{"DNA", "8", "Au", "GDP", "이순신", "에펠 탑", "H2O", "금", "ㄱ"}) {
            String h = text(a).hint();
            if (h == null) continue;
            assertThat(QuizQuestion.normalize(h))
                    .as("정답 " + a + " 의 힌트 " + h).isNotEqualTo(QuizQuestion.normalize(a));
        }
    }

    @Test
    void 객관식에는_힌트를_주지_않는다() {
        assertThat(choice(0).hint()).isNull();
        assertThat(text("이순신").hint()).isEqualTo("ㅇㅅㅅ");
    }
}
