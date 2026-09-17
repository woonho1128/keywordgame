package com.wordplay.snakes;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 랜덤 보드 생성 제약.
 *
 * <p>매판 새로 뽑는 게 요구사항인데, 제약 없이 뽑으면 판이 자주 망가진다. 실측으로
 * 보드 300개를 돌려본 결과 19%가 평균 15~45턴을 벗어났고 최악은 평균 180턴이었다.
 * 아래 제약들이 그걸 막는다 — 하나라도 풀리면 다시 그런 판이 나온다.
 */
class SnakesBoardTest {

    /** 시드를 바꿔가며 여러 보드를 검사한다(한 판만 보면 우연히 통과한다). */
    private static final int SAMPLES = 60;

    @Test
    void 같은_시드면_같은_보드가_나온다() {
        SnakesBoard a = SnakesBoard.random(4, 12345L);
        SnakesBoard b = SnakesBoard.random(4, 12345L);
        assertThat(a.jumpsView()).isEqualTo(b.jumpsView());
        assertThat(a.seed()).isEqualTo(12345L);
    }

    @Test
    void 시드가_다르면_보드도_달라진다() {
        Set<Map<Integer, Integer>> seen = new HashSet<>();
        for (int i = 0; i < 20; i++) seen.add(SnakesBoard.random(4, i).jumpsView());
        assertThat(seen).as("매판 랜덤이어야 한다").hasSizeGreaterThan(15);
    }

    @Test
    void 인원이_늘면_판이_작아진다() {
        assertThat(SnakesBoard.random(2, 1).cols()).isEqualTo(10);
        assertThat(SnakesBoard.random(4, 1).cols()).isEqualTo(10);
        assertThat(SnakesBoard.random(5, 1).cols()).isEqualTo(8);
        assertThat(SnakesBoard.random(7, 1).cols()).isEqualTo(8);
        assertThat(SnakesBoard.random(8, 1).cols()).isEqualTo(7);
        assertThat(SnakesBoard.random(10, 1).cols()).isEqualTo(7);
    }

    @Test
    void 한_칸에는_끝점이_하나뿐이다() {
        // 겹치면 사다리 위에 뱀 머리가 오는 연쇄가 생겨 판정이 모호해진다.
        for (int s = 0; s < SAMPLES; s++) {
            SnakesBoard b = SnakesBoard.random(4, s);
            Set<Integer> endpoints = new HashSet<>();
            b.jumpsView().forEach((from, to) -> { endpoints.add(from); endpoints.add(to); });
            assertThat(endpoints).as("시드 " + s).hasSize(b.jumpsView().size() * 2);
        }
    }

    @Test
    void 시작칸과_도착칸은_비어_있다() {
        for (int s = 0; s < SAMPLES; s++) {
            SnakesBoard b = SnakesBoard.random(4, s);
            assertThat(b.jump(1)).as("시드 " + s + " 시작칸").isEqualTo(1);
            assertThat(b.jump(b.size())).as("시드 " + s + " 도착칸").isEqualTo(b.size());
            b.jumpsView().forEach((from, to) -> {
                assertThat(to).isNotEqualTo(b.size());
                assertThat(to).isNotEqualTo(1);
            });
        }
    }

    @Test
    void 사다리는_위로_뱀은_아래로_간다() {
        for (int s = 0; s < SAMPLES; s++) {
            SnakesBoard b = SnakesBoard.random(4, s);
            for (int[] l : b.ladders()) assertThat(l[1]).as("사다리").isGreaterThan(l[0]);
            for (int[] n : b.snakes()) assertThat(n[1]).as("뱀").isLessThan(n[0]);
        }
    }

    @Test
    void 점프는_최소_한줄_최대_네줄을_건넌다() {
        for (int s = 0; s < SAMPLES; s++) {
            SnakesBoard b = SnakesBoard.random(4, s);
            int cols = b.cols();
            final int seed = s;
            b.jumpsView().forEach((from, to) -> {
                int span = Math.abs(SnakesBoard.rowOf(to, cols) - SnakesBoard.rowOf(from, cols));
                assertThat(span).as("시드 " + seed + " " + from + "→" + to)
                        .isBetween(1, SnakesBoard.MAX_ROW_SPAN);
            });
        }
    }

    @Test
    void 마지막_줄의_뱀은_하나까지만() {
        // 도착 직전에 뱀이 여럿이면 다 와서 계속 미끄러지는 판이 된다.
        for (int s = 0; s < SAMPLES; s++) {
            SnakesBoard b = SnakesBoard.random(4, s);
            int lastRow = b.cols() - 1, n = 0;
            for (int[] snake : b.snakes())
                if (SnakesBoard.rowOf(snake[0], b.cols()) == lastRow) n++;
            assertThat(n).as("시드 " + s).isLessThanOrEqualTo(SnakesBoard.TAIL_SNAKE_MAX);
        }
    }

    @Test
    void 뽑힌_보드는_적당한_길이의_판이_된다() {
        // 회귀 방어: 생성 제약이 약해지면 여기서 먼저 터진다.
        double worst = 0;
        int over = 0;
        for (int s = 0; s < SAMPLES; s++) {
            double avg = SnakesBoard.random(4, s).averageTurns(new Random(s));
            worst = Math.max(worst, avg);
            if (avg < SnakesBoard.MIN_AVG_TURNS || avg > SnakesBoard.MAX_AVG_TURNS) over++;
        }
        assertThat(over).as("범위를 벗어난 보드 수").isLessThanOrEqualTo(SAMPLES / 10);
        assertThat(worst).as("최악 보드 평균 턴").isLessThan(80);
    }
}
