package com.wordplay.lexio;

import com.wordplay.lexio.dto.LexioStateResponse;
import com.wordplay.lexio.dto.NewLexioRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LexioGameTest {

    /** suit 0구름 1별 2달 3해, num 1~15 → 타일 id. */
    private static int id(int suit, int num) { return suit * 15 + (num - 1); }
    private static long str(List<Integer> tiles) { return LexioGame.evalForTest(tiles).strength(); }
    private static LexioGame.HandType type(List<Integer> tiles) { return LexioGame.evalForTest(tiles).type(); }

    @Test
    void 싱글_세기_2가_가장_강하고_3구름이_가장_약함() {
        // 해2가 최강 싱글, 구름3이 최약
        assertThat(str(List.of(id(3, 2)))).isGreaterThan(str(List.of(id(0, 1))));  // 해2 > 구름1
        assertThat(str(List.of(id(0, 1)))).isGreaterThan(str(List.of(id(3, 15)))); // 구름1 > 해15
        assertThat(str(List.of(id(0, 3)))).isLessThan(str(List.of(id(1, 3))));     // 구름3 < 별3(무늬)
    }

    @Test
    void 페어_트리플_판별() {
        assertThat(type(List.of(id(0, 5), id(1, 5)))).isEqualTo(LexioGame.HandType.PAIR);
        assertThat(LexioGame.evalForTest(List.of(id(0, 5), id(1, 6)))).isNull(); // 숫자 다르면 무효
        assertThat(type(List.of(id(0, 7), id(1, 7), id(2, 7)))).isEqualTo(LexioGame.HandType.TRIPLE);
    }

    @Test
    void 다섯장_조합_판별() {
        assertThat(type(List.of(id(0, 3), id(1, 4), id(2, 5), id(3, 6), id(0, 7))))
                .isEqualTo(LexioGame.HandType.STRAIGHT);
        assertThat(type(List.of(id(3, 3), id(3, 5), id(3, 7), id(3, 9), id(3, 11))))
                .isEqualTo(LexioGame.HandType.FLUSH);
        assertThat(type(List.of(id(0, 8), id(1, 8), id(2, 8), id(0, 9), id(1, 9))))
                .isEqualTo(LexioGame.HandType.FULLHOUSE);
        assertThat(type(List.of(id(0, 10), id(1, 10), id(2, 10), id(3, 10), id(0, 11))))
                .isEqualTo(LexioGame.HandType.FOURPLUSONE);
        assertThat(type(List.of(id(3, 3), id(3, 4), id(3, 5), id(3, 6), id(3, 7))))
                .isEqualTo(LexioGame.HandType.STRAIGHTFLUSH);
    }

    @Test
    void 다섯장_족보_세기_순서() {
        long straight = str(List.of(id(0, 3), id(1, 4), id(2, 5), id(3, 6), id(0, 7)));
        long flush = str(List.of(id(3, 3), id(3, 5), id(3, 7), id(3, 9), id(3, 11)));
        long full = str(List.of(id(0, 8), id(1, 8), id(2, 8), id(0, 9), id(1, 9)));
        long four = str(List.of(id(0, 10), id(1, 10), id(2, 10), id(3, 10), id(0, 11)));
        long sf = str(List.of(id(3, 3), id(3, 4), id(3, 5), id(3, 6), id(3, 7)));
        assertThat(straight).isLessThan(flush);
        assertThat(flush).isLessThan(full);
        assertThat(full).isLessThan(four);
        assertThat(four).isLessThan(sf);
    }

    @Test
    void 같은족보_높은쪽이_이김() {
        // 페어: 9페어 > 5페어
        assertThat(str(List.of(id(0, 9), id(1, 9)))).isGreaterThan(str(List.of(id(0, 5), id(1, 5))));
        // 스트레이트: 상단 자연수 높은 쪽이 강함 (11-15 > 3-7)
        long low = str(List.of(id(0, 3), id(1, 4), id(2, 5), id(3, 6), id(0, 7)));
        long high = str(List.of(id(0, 11), id(1, 12), id(2, 13), id(3, 14), id(0, 15)));
        assertThat(high).isGreaterThan(low);
    }

    @Test
    void 시작하면_인원별로_딜된다() {
        LexioGame g = new LexioGame();
        g.newGame("host", "p0", "WHITE", "SINGLE", 40);
        g.addBot("host", "NORMAL"); g.addBot("host", "NORMAL"); g.addBot("host", "NORMAL"); // 4인
        LexioStateResponse s = g.start("host");
        assertThat(s.status()).isEqualTo("PLAYING");
        assertThat(s.theme()).isEqualTo("WHITE");
        // 정규 규칙: 4인이면 1~13 범위로 13장씩
        assertThat(s.myTiles()).hasSize(13);
        assertThat(s.players()).allSatisfy(p -> assertThat(p.tileCount()).isEqualTo(13));
        assertThat(s.myTiles()).allSatisfy(id -> assertThat((id % 15) + 1).isBetween(1, 13)); // 14·15 제외
        assertThat(s.currentTurnSeat()).isBetween(1, 4);
    }

    @Test
    void 인원별_사용_숫자범위와_장수가_정규규칙과_같다() {
        // 3인: 1~9, 12장씩(36장)
        LexioGame g3 = new LexioGame();
        g3.newGame("h", "p", "BLACK", "SINGLE", 40);
        g3.addBot("h", "NORMAL"); g3.addBot("h", "NORMAL"); // 3인
        LexioStateResponse s3 = g3.start("h");
        assertThat(s3.myTiles()).hasSize(12);
        assertThat(s3.myTiles()).allSatisfy(id -> assertThat((id % 15) + 1).isBetween(1, 9));

        // 5인: 1~15, 12장씩(60장)
        LexioGame g5 = new LexioGame();
        g5.newGame("h", "p", "BLACK", "SINGLE", 40);
        for (int i = 0; i < 4; i++) g5.addBot("h", "NORMAL"); // 5인
        LexioStateResponse s5 = g5.start("h");
        assertThat(s5.myTiles()).hasSize(12);
        assertThat(s5.players()).allSatisfy(p -> assertThat(p.tileCount()).isEqualTo(12));
    }
}
