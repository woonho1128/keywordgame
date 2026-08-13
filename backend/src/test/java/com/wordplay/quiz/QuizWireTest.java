package com.wordplay.quiz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordplay.quiz.dto.QuizState;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프론트가 실제로 받는 JSON을 그대로 검사한다.
 *
 * <p>"4지선다 답이 클릭되지 않는다"는 제보. UI는 mock으로 정상 동작했으므로 응답
 * 내용을 의심한다 — 보기 목록이 비었거나 canAnswer가 false면 버튼이 없거나 잠긴다.
 */
class QuizWireTest {

    private static QuizGame game() {
        QuizBank b = new QuizBank(null);
        ReflectionTestUtils.setField(b, "dailyCallLimit", 0);
        return new QuizGame("host", "우노", 3, 5, 30, null, null, b);
    }

    @Test
    void 객관식_문제에는_보기가_넷_실리고_답을_낼_수_있다() throws Exception {
        QuizGame g = game();
        g.start("host");
        ObjectMapper om = new ObjectMapper();
        for (int i = 0; i < 5; i++) {
            QuizState st = g.me("host");
            JsonNode n = om.readTree(om.writeValueAsString(st));
            // 어떤 종류가 나오든 프론트가 기대하는 모양이어야 한다.
            assertThat(n.hasNonNull("choices")).as("choices는 null이 아니라 배열").isTrue();
            assertThat(n.hasNonNull("canAnswer")).isTrue();
            if ("CHOICE".equals(st.kind())) {
                assertThat(n.get("choices")).hasSize(4);
                assertThat(n.get("canAnswer").asBoolean()).as("버튼이 열려 있어야 한다").isTrue();
                g.answer("host", "0");
                assertThat(g.me("host").myAnswer()).isNotNull();
                return;
            }
            ReflectionTestUtils.setField(g, "deadline", System.currentTimeMillis() - 1);
            g.me("host");
            ReflectionTestUtils.setField(g, "revealAt", System.currentTimeMillis() - 1);
            g.me("host");
        }
        throw new AssertionError("객관식 문제가 한 번도 나오지 않았다");
    }
}
