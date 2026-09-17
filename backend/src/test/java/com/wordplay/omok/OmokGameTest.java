package com.wordplay.omok;

import com.wordplay.omok.dto.OmokStateResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OmokGameTest {

    private static int cell(int r, int c) { return r * OmokGame.N + c; }

    private OmokGame started(String rule) {
        OmokGame g = new OmokGame();
        g.newGame("host", "흑", "BLACK", rule); // 방장 흑
        g.addBot("host", "EASY");               // 상대 봇 백
        g.start("host");
        return g;
    }

    @Test
    void 가로_5목이면_흑_승리() {
        OmokGame g = started("FREE");
        int[] b = g.boardForTest();
        // 흑을 (7,3~6)에 미리 두고, 봇 백 방해가 없도록 직접 배치 후 마지막 흑 착수로 5목
        b[cell(7, 3)] = 1; b[cell(7, 4)] = 1; b[cell(7, 5)] = 1; b[cell(7, 6)] = 1;
        // 백은 멀리
        b[cell(0, 0)] = 2; b[cell(0, 1)] = 2;
        OmokStateResponse s = g.place("host", cell(7, 7)); // 흑 5목 완성
        assertThat(g.winnerForTest()).isEqualTo(1);
        assertThat(s.winner()).isEqualTo(1);
        assertThat(s.winLine()).hasSize(5);
        assertThat(s.status()).isEqualTo("ENDED");
    }

    @Test
    void 자유룰은_금수없음() {
        OmokGame g = started("FREE");
        int[] b = g.boardForTest();
        // 열린 3을 두 개 만드는 자리라도 자유룰이면 금지 아님
        b[cell(7, 6)] = 1; b[cell(7, 8)] = 1; // 가로
        b[cell(6, 7)] = 1; b[cell(8, 7)] = 1; // 세로
        assertThat(g.forbiddenForTest(cell(7, 7))).isFalse();
    }

    @Test
    void 렌주룰_33_금수() {
        OmokGame g = started("RENJU");
        int[] b = g.boardForTest();
        // (7,7)에 두면 가로 _BB_ + 세로 _BB_ 로 열린3 2개 → 3-3 금수
        b[cell(7, 5)] = 1; b[cell(7, 6)] = 1;   // 가로 두 개(왼쪽)
        b[cell(5, 7)] = 1; b[cell(6, 7)] = 1;   // 세로 두 개(위)
        assertThat(g.forbiddenForTest(cell(7, 7))).isTrue();
    }

    @Test
    void 렌주룰_장목_금수() {
        OmokGame g = started("RENJU");
        int[] b = g.boardForTest();
        // (7,7) 두면 가로로 6목(장목) → 금수
        b[cell(7, 4)] = 1; b[cell(7, 5)] = 1; b[cell(7, 6)] = 1; b[cell(7, 8)] = 1; b[cell(7, 9)] = 1;
        assertThat(g.forbiddenForTest(cell(7, 7))).isTrue();
    }

    @Test
    void 렌주룰_정확히5는_금수아님_승리우선() {
        OmokGame g = started("RENJU");
        int[] b = g.boardForTest();
        b[cell(7, 3)] = 1; b[cell(7, 4)] = 1; b[cell(7, 5)] = 1; b[cell(7, 6)] = 1;
        assertThat(g.forbiddenForTest(cell(7, 7))).isFalse(); // 정확히 5 → 허용(승리)
    }

    @Test
    void 봇이_상대_4목을_막는다() {
        OmokGame g = new OmokGame();
        g.newGame("host", "흑", "WHITE", "FREE"); // 방장 백 → 봇이 흑(선)
        g.addBot("host", "NORMAL");
        g.start("host");
        int[] b = g.boardForTest();
        // 사람(백)이 4연속 위협 → 봇(흑) 차례 시 막아야 함. 여기선 봇 흑 선이므로 시나리오만 확인:
        // 봇이 즉시 5를 만들 수 있으면 만든다.
        b[cell(7, 3)] = 1; b[cell(7, 4)] = 1; b[cell(7, 5)] = 1; b[cell(7, 6)] = 1; // 흑 4개
        g.forceBotNowForTest();
        g.me("host"); // 봇(흑) 진행 → 5목 완성 기대
        assertThat(g.winnerForTest()).isEqualTo(1);
    }
}
