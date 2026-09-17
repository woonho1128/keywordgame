package com.wordplay.quiz;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 응답 파싱과 비용 방어.
 *
 * <p>모델 응답은 언제든 형식이 어긋날 수 있다. 한 문제가 깨져도 나머지는 쓰고, 못 쓸
 * 문제(보기 3개, 정답 범위 밖, 문장형 주관식 답)는 조용히 버려야 한다.
 */
class QuizBankTest {

    private static QuizBank bank() {
        QuizBank b = new QuizBank(null);
        ReflectionTestUtils.setField(b, "dailyCallLimit", 200);
        return b;
    }

    @Test
    void 정상_응답을_문제로_바꾼다() {
        String raw = """
                [
                  {"kind":"CHOICE","topic":"한국사","question":"훈민정음을 만든 임금은?",
                   "choices":["태종","세종","성종","정조"],"answerIndex":1,"explain":"1443년 창제"},
                  {"kind":"TEXT","topic":"동물","question":"목이 가장 긴 육상 동물은?",
                   "answers":["기린"],"explain":"목뼈는 7개"}
                ]
                """;
        List<QuizQuestion> qs = bank().parse(raw, 5);
        assertThat(qs).hasSize(2);
        assertThat(qs.get(0).kind()).isEqualTo(QuizQuestion.Kind.CHOICE);
        assertThat(qs.get(0).answerLabel()).isEqualTo("세종");
        assertThat(qs.get(1).kind()).isEqualTo(QuizQuestion.Kind.TEXT);
        assertThat(qs.get(1).hint()).isEqualTo("ㄱㄹ");
        assertThat(qs).allMatch(q -> q.level() == 5);
    }

    @Test
    void 코드펜스로_감싸도_읽는다() {
        String raw = "```json\n[{\"kind\":\"TEXT\",\"question\":\"수도?\",\"answers\":[\"서울\"],\"explain\":\"x\"}]\n```";
        assertThat(bank().parse(raw, 3)).hasSize(1);
    }

    @Test
    void 앞뒤에_설명이_붙어도_배열만_뽑아낸다() {
        String raw = "알겠습니다. 아래와 같습니다.\n[{\"kind\":\"TEXT\",\"question\":\"수도?\",\"answers\":[\"서울\"],\"explain\":\"x\"}]\n도움이 되었기를!";
        assertThat(bank().parse(raw, 3)).hasSize(1);
    }

    @Test
    void 형식이_어긋난_문제만_버리고_나머지는_쓴다() {
        String raw = """
                [
                  {"kind":"CHOICE","question":"보기가 셋뿐","choices":["a","b","c"],"answerIndex":0},
                  {"kind":"CHOICE","question":"정답 번호가 범위 밖","choices":["a","b","c","d"],"answerIndex":7},
                  {"kind":"CHOICE","question":"보기가 겹친다","choices":["a","a","b","c"],"answerIndex":0},
                  {"kind":"TEXT","question":"답이 없다","answers":[]},
                  {"kind":"TEXT","question":"답이 문장이다","answers":["아주 길고 설명 같은 문장으로 된 답입니다 정말"]},
                  {"kind":"CHOICE","question":"이건 멀쩡하다","choices":["a","b","c","d"],"answerIndex":2,"explain":"ok"}
                ]
                """;
        List<QuizQuestion> qs = bank().parse(raw, 5);
        assertThat(qs).as("멀쩡한 하나만 남아야 한다").hasSize(1);
        assertThat(qs.get(0).question()).isEqualTo("이건 멀쩡하다");
    }

    @Test
    void 같은_지문은_두_번_내지_않는다() {
        QuizBank b = bank();
        String raw = "[{\"kind\":\"TEXT\",\"question\":\"수도는?\",\"answers\":[\"서울\"],\"explain\":\"x\"}]";
        assertThat(b.parse(raw, 3)).hasSize(1);
        assertThat(b.parse(raw, 3)).as("두 번째는 중복이라 버린다").isEmpty();
        // 띄어쓰기만 다른 것도 같은 문제로 본다
        assertThat(b.parse("[{\"kind\":\"TEXT\",\"question\":\"수 도는 ?\",\"answers\":[\"서울\"]}]", 3)).isEmpty();
    }

    @Test
    void 쓰레기_응답은_빈_목록() {
        QuizBank b = bank();
        assertThat(b.parse("", 5)).isEmpty();
        assertThat(b.parse("모르겠습니다", 5)).isEmpty();
        assertThat(b.parse("[not json", 5)).isEmpty();
    }

    @Test
    void AI가_없으면_내장_문제로_판을_채운다() {
        // 키가 없으면 client가 null → 생성 불가. 그래도 문제는 나와야 게임이 시작된다.
        QuizBank b = bank();
        assertThat(b.aiAvailable()).isFalse();
        List<QuizQuestion> qs = b.take(5, 10);
        assertThat(qs).hasSize(10);
        assertThat(qs).extracting(QuizQuestion::id).doesNotHaveDuplicates();
        assertThat(b.callsToday()).as("호출은 하지 않는다").isZero();
    }

    @Test
    void 내장_문제는_요청_난이도에_가까운_것부터_준다() {
        QuizBank b = bank();
        double easyAvg = b.take(1, 5).stream().mapToInt(QuizQuestion::level).average().orElse(0);
        double hardAvg = b.take(10, 5).stream().mapToInt(QuizQuestion::level).average().orElse(0);
        assertThat(easyAvg).as("쉬운 난이도 요청").isLessThan(hardAvg);
    }

    @Test
    void 난이도_설명이_구간마다_다르다() {
        // 숫자만 주면 모델이 1과 3을 구분하지 못한다. 말로 풀어주는 문장이 달라야 한다.
        assertThat(QuizBank.difficultyHint(1)).isNotEqualTo(QuizBank.difficultyHint(5));
        assertThat(QuizBank.difficultyHint(5)).isNotEqualTo(QuizBank.difficultyHint(10));
        assertThat(QuizBank.difficultyHint(1)).contains("쉬운");
        assertThat(QuizBank.difficultyHint(10)).contains("마니아");
    }

    @Test
    void 시스템_프롬프트는_출제_사고를_막는_규칙을_담는다() {
        // 틀린 문제·복수 정답·최신 정보는 퀴즈를 망가뜨리는 3대 원인이다.
        assertThat(QuizBank.SYSTEM).contains("사실만");
        assertThat(QuizBank.SYSTEM).contains("정답이 단 하나");
        assertThat(QuizBank.SYSTEM).contains("최신 정보");
        assertThat(QuizBank.SYSTEM).contains("answers");     // 주관식 허용 표기 지시
    }
}
