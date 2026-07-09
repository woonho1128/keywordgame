package com.wordplay.drawgame;

import com.wordplay.drawgame.dto.DrawGameStateResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DrawGameTest {

    private DrawGame started3(String topic) {
        DrawGame g = new DrawGame();
        g.newGame("host", "p0", "GARTIC", topic, 60, 120);
        g.join("c1", "p1");
        g.join("c2", "p2");
        g.start("host");
        return g;
    }

    private final String IMG = "data:image/png;base64,AAAA";

    @Test
    void 최소인원_미달() {
        DrawGame g = new DrawGame();
        g.newGame("host", "p0", "GARTIC", "FREE", 60, 120);
        assertThatThrownBy(() -> g.start("host")).hasMessageContaining("최소 3명");
    }

    @Test
    void FREE_시작문장_라운드0_후_그리기() {
        DrawGame g = started3("FREE");
        assertThat(g.me("host").round()).isEqualTo(0);
        assertThat(g.me("host").taskType()).isEqualTo("WRITE_INITIAL");
        g.submit("host", "TEXT", "사과");
        g.submit("c1", "TEXT", "바나나");
        DrawGameStateResponse s = g.submit("c2", "TEXT", "포도"); // 전원 제출 → 라운드1
        assertThat(s.round()).isEqualTo(1);
        assertThat(s.taskType()).isEqualTo("DRAW");
        assertThat(s.promptText()).isIn("사과", "바나나", "포도"); // 넘겨받은 남의 문장
    }

    @Test
    void RANDOM_모드는_제시어_자동_1라운드부터() {
        DrawGame g = started3("RANDOM");
        DrawGameStateResponse s = g.me("host");
        assertThat(s.round()).isEqualTo(1);
        assertThat(s.taskType()).isEqualTo("DRAW");
        assertThat(s.promptText()).isNotBlank(); // 자동 제시어
    }

    @Test
    void 전체_진행_후_공개() {
        DrawGame g = started3("RANDOM"); // round 1부터, 총 3라운드(1,2 진행)
        // 라운드1: 그림
        g.submit("host", "IMAGE", IMG);
        g.submit("c1", "IMAGE", IMG);
        g.submit("c2", "IMAGE", IMG);
        // 라운드2: 문장
        g.submit("host", "TEXT", "설명");
        g.submit("c1", "TEXT", "설명");
        DrawGameStateResponse s = g.submit("c2", "TEXT", "설명");
        assertThat(s.status()).isEqualTo("REVEAL");
        assertThat(s.albums()).hasSize(3);
        assertThat(s.albums().get(0).steps()).hasSize(3); // 제시어+그림+문장
    }
}
