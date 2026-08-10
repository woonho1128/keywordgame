package com.wordplay.snakes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 뱀과 사다리 보드. 매판 새로 만든다.
 *
 * <p>무작정 랜덤하게 놓으면 판이 자주 망가진다. 실측(보드 300개 × 300판)으로 확인한
 * 결과, 제약 없이 뽑으면 19%가 평균 15~45턴 범위를 벗어나고 최악은 평균 180턴짜리
 * (정상의 6배) 보드가 나온다. 그래서 아래 제약을 걸고, 그래도 이상하면 다시 뽑는다.
 *
 * <ol>
 *   <li>한 칸에는 끝점이 하나만 — 뱀 머리가 사다리 위에 겹치는 연쇄를 원천 차단한다.</li>
 *   <li>시작 칸과 도착 칸에는 아무것도 두지 않는다.</li>
 *   <li>사다리는 반드시 위로, 뱀은 반드시 아래로 간다.</li>
 *   <li>최소 한 줄은 건너뛴다 — 같은 줄 안에서 끝나면 눈에도 안 띄고 효과도 미미하다.</li>
 *   <li>최대 {@value #MAX_ROW_SPAN}줄까지만 — 99에서 1로 떨어지는 재앙을 막는다.</li>
 *   <li>마지막 줄의 뱀은 {@value #TAIL_SNAKE_MAX}개까지 — 다 와서 계속 미끄러지는 판 방지.</li>
 * </ol>
 *
 * <p>보드는 불변이다. 같은 seed면 같은 보드가 나오므로 재현·신고 추적이 가능하다.
 */
public final class SnakesBoard {

    /** 점프가 건널 수 있는 최대 줄 수. */
    static final int MAX_ROW_SPAN = 4;
    /** 마지막 줄에 놓을 수 있는 뱀 머리 수. */
    static final int TAIL_SNAKE_MAX = 1;
    /** 검증에 쓰는 1인 시뮬레이션 판 수와 허용 평균 턴 범위. */
    static final int SIM_GAMES = 200;
    static final double MIN_AVG_TURNS = 15, MAX_AVG_TURNS = 45;
    /** 범위를 만족하는 보드를 찾기 위한 최대 재생성 횟수. */
    static final int MAX_ATTEMPTS = 20;

    private final int cols, size;
    private final long seed;
    /** 출발 칸 -> 도착 칸. 도착 > 출발이면 사다리, 작으면 뱀. */
    private final Map<Integer, Integer> jumps;

    private SnakesBoard(int cols, long seed, Map<Integer, Integer> jumps) {
        this.cols = cols;
        this.size = cols * cols;
        this.seed = seed;
        this.jumps = jumps;
    }

    public int cols() { return cols; }
    public int size() { return size; }
    public long seed() { return seed; }

    /** 이 칸에서 이동할 곳. 뱀도 사다리도 없으면 같은 칸을 돌려준다. */
    public int jump(int square) { return jumps.getOrDefault(square, square); }

    /** 사다리 목록(출발, 도착). 화면에 그리기 위해 공개한다 — 어차피 보드는 모두가 본다. */
    public List<int[]> ladders() { return pairs(true); }
    public List<int[]> snakes() { return pairs(false); }

    private List<int[]> pairs(boolean up) {
        List<int[]> out = new ArrayList<>();
        jumps.forEach((from, to) -> { if ((to > from) == up) out.add(new int[]{from, to}); });
        out.sort((a, b) -> a[0] - b[0]);
        return out;
    }

    // ── 생성 ──

    /**
     * 인원 수에 맞는 보드를 만든다.
     *
     * <p>인원이 늘면 자기 차례가 늦게 돌아와 체감 시간이 길어지므로 판을 줄인다.
     */
    public static SnakesBoard random(int players, long seed) {
        int cols = colsFor(players);
        int jumpsEach = jumpsFor(cols);
        Random rng = new Random(seed);

        SnakesBoard best = null;
        double bestGap = Double.MAX_VALUE;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            SnakesBoard b = build(cols, seed, jumpsEach, rng);
            double avg = b.averageTurns(new Random(seed + attempt));
            if (avg >= MIN_AVG_TURNS && avg <= MAX_AVG_TURNS) return b;
            // 범위를 못 맞추면 가장 가까운 것을 들고 간다(무한 루프 방지).
            double gap = avg < MIN_AVG_TURNS ? MIN_AVG_TURNS - avg : avg - MAX_AVG_TURNS;
            if (gap < bestGap) { bestGap = gap; best = b; }
        }
        return best;
    }

    static int colsFor(int players) { return players <= 4 ? 10 : players <= 7 ? 8 : 7; }
    static int jumpsFor(int cols) { return cols >= 10 ? 8 : cols >= 8 ? 6 : 5; }

    private static SnakesBoard build(int cols, long seed, int each, Random rng) {
        int size = cols * cols;
        Set<Integer> used = new HashSet<>(List.of(1, size));   // 시작·도착 칸은 비워둔다
        Map<Integer, Integer> jumps = new LinkedHashMap<>();
        for (int i = 0; i < each; i++) place(cols, used, jumps, rng, true);
        for (int i = 0; i < each; i++) place(cols, used, jumps, rng, false);
        return new SnakesBoard(cols, seed, jumps);
    }

    /** 사다리(up=true) 또는 뱀 하나를 놓는다. 자리를 못 찾으면 조용히 건너뛴다. */
    private static void place(int cols, Set<Integer> used, Map<Integer, Integer> jumps,
                              Random rng, boolean up) {
        int size = cols * cols, lastRow = cols - 1;
        for (int tries = 0; tries < 400; tries++) {
            int from = 2 + rng.nextInt(size - 2);          // 1과 마지막 칸 제외
            if (used.contains(from)) continue;
            int fromRow = rowOf(from, cols);

            int loRow, hiRow;
            if (up) {
                loRow = fromRow + 1;
                hiRow = Math.min(fromRow + MAX_ROW_SPAN, lastRow);
            } else {
                loRow = Math.max(fromRow - MAX_ROW_SPAN, 0);
                hiRow = fromRow - 1;
                if (fromRow == lastRow && countTailSnakes(jumps, cols) >= TAIL_SNAKE_MAX) continue;
            }
            if (loRow > hiRow) continue;

            int row = loRow + rng.nextInt(hiRow - loRow + 1);
            int to = row * cols + 1 + rng.nextInt(cols);
            if (to == size || to == 1 || used.contains(to)) continue;
            if (up ? to <= from : to >= from) continue;

            used.add(from); used.add(to);
            jumps.put(from, to);
            return;
        }
    }

    /** 0-based 줄 번호. 보아스트로피돈 배치라도 번호 기준 줄은 같다. */
    static int rowOf(int square, int cols) { return (square - 1) / cols; }

    private static int countTailSnakes(Map<Integer, Integer> jumps, int cols) {
        int lastRow = cols - 1, n = 0;
        for (var e : jumps.entrySet())
            if (e.getValue() < e.getKey() && rowOf(e.getKey(), cols) == lastRow) n++;
        return n;
    }

    // ── 검증 ──

    /** 1인이 혼자 완주하는 데 걸리는 평균 턴 수. 너무 짧거나 긴 보드를 걸러낸다. */
    double averageTurns(Random rng) {
        long total = 0;
        for (int g = 0; g < SIM_GAMES; g++) total += soloTurns(rng);
        return (double) total / SIM_GAMES;
    }

    private int soloTurns(Random rng) {
        int pos = 0, turns = 0;
        while (pos < size && turns < 3000) {
            turns++;
            int extra = 0;
            while (true) {
                int d = 1 + rng.nextInt(6);
                if (pos + d <= size) pos = jump(pos + d);
                if (pos >= size) break;
                if (d != 6 || ++extra >= SnakesGame.MAX_EXTRA_ROLLS) break;
            }
        }
        return turns;
    }

    /** 테스트·디버깅용 스냅샷. */
    Map<Integer, Integer> jumpsView() { return new HashMap<>(jumps); }
}
