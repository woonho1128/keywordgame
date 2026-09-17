package com.wordplay.othello;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 오델로 오프닝 북.
 *
 * 목적: 상위 난이도(초고수·그마·신화)가 초반을 "매판 다른 정석 계열"로 시작하게 해
 * 변주(다양성)를 살리되, 검증된 사운드 라인만 담아 강함 손실 0을 유지한다.
 * 북을 벗어나거나(사람이 정석 밖으로 두면) 라인이 끝나면 그 순간부터 엔진(미니맥스)이 이어받는다.
 *
 * 표기: a~h(열) 1~8(행). cell = row*8 + col, col = letter-'a', row = digit-1.
 * 예) f5 → col=5, row=4 → cell 37.
 *
 * 좌우/회전 대칭인 오프닝 4방향을 모두 매칭하기 위해, 아래 정규(canonical) 라인들을
 * 반(dihedral) 8대칭으로 변환해 트리에 함께 등록한다. 덕분에 봇이 흑이든 백이든,
 * 사람이 f5/e6/c4/d3 어느 방향으로 열든 북이 작동한다.
 */
final class OthelloOpeningBook {
    private OthelloOpeningBook() {}

    /** key = 지금까지 둔 수순(cell들을 ','로 연결). value = 그 국면에서 둘 수 있는 등록 수 후보. */
    private static final Map<String, int[]> BOOK = new ConcurrentHashMap<>();

    // 검증된 사운드 오프닝 라인들(정규형). 첫 수 f5 기준.
    //  - 대각형(Diagonal): f5 d6 ...
    //  - 수직형(Perpendicular): f5 f6 ...
    //  - 평행형(Parallel): f5 f4 (이후 엔진에 인계)
    private static final String[][] CANONICAL = {
        {"f5", "d6", "c3", "d3"},  // 대각형
        {"f5", "d6", "c3", "f4"},  // 대각형 분기
        {"f5", "d6", "c4"},        // 대각형 분기
        {"f5", "f6", "e6", "f4"},  // 수직형
        {"f5", "f4"},              // 평행형(시작만, 이후 엔진)
    };

    // 표준 시작 국면(흑 d5·e4 / 백 d4·e5, 흑 선)을 보존하는 대칭만 사용한다.
    // 8대칭 중 항등·180°·주대각 반사·반대각 반사 4개만 흑백 배치를 지킨다.
    // (90°·270°·상하·좌우 반사는 흑백이 뒤바뀌어 흑이 둘 수 없는 자리로 첫 수가 가므로 제외.)
    private static final int[] SYMMETRIES = {0, 2, 6, 7};

    static {
        for (String[] line : CANONICAL) {
            int[] cells = new int[line.length];
            for (int i = 0; i < line.length; i++) cells[i] = cellOf(line[i]);
            for (int s : SYMMETRIES) addLine(transform(cells, s));
        }
    }

    private static int cellOf(String n) {
        int col = n.charAt(0) - 'a';
        int row = n.charAt(1) - '1';
        return row * 8 + col;
    }

    /** 반 8대칭 변환. s: 0 항등, 1~3 회전, 4~7 반사. */
    private static int[] transform(int[] cells, int s) {
        int[] out = new int[cells.length];
        for (int i = 0; i < cells.length; i++) {
            int r = cells[i] / 8, c = cells[i] % 8, nr, nc;
            switch (s) {
                case 0 -> { nr = r; nc = c; }                 // 항등
                case 1 -> { nr = c; nc = 7 - r; }             // 90° 회전
                case 2 -> { nr = 7 - r; nc = 7 - c; }         // 180°
                case 3 -> { nr = 7 - c; nc = r; }             // 270°
                case 4 -> { nr = r; nc = 7 - c; }             // 좌우 반사
                case 5 -> { nr = 7 - r; nc = c; }             // 상하 반사
                case 6 -> { nr = c; nc = r; }                 // 주대각 반사
                default -> { nr = 7 - c; nc = 7 - r; }        // 반대각 반사
            }
            out[i] = nr * 8 + nc;
        }
        return out;
    }

    /** 라인의 각 접두(prefix)에 대해 "다음 수"를 후보로 등록(중복 제거하며 병합). */
    private static void addLine(int[] cells) {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            String k = key.toString();
            int[] prev = BOOK.get(k);
            Set<Integer> cand = new LinkedHashSet<>();
            if (prev != null) for (int v : prev) cand.add(v);
            cand.add(cells[i]);
            int[] arr = new int[cand.size()]; int j = 0;
            for (int v : cand) arr[j++] = v;
            BOOK.put(k, arr);
            if (i > 0) key.append(',');
            key.append(cells[i]);
        }
    }

    /**
     * 현재 수순(history)에 대한 북 수를 고른다.
     * @param history 지금까지 둔 cell들(양쪽 모두, 착수 순서대로)
     * @param legal   현재 착수 가능한 수(합법성 가드 — 등록 수라도 실제로 둘 수 있어야 함)
     * @return 북에서 고른 수, 없으면 null(→ 엔진이 이어받음)
     */
    static Integer pick(List<Integer> history, List<Integer> legal) {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < history.size(); i++) { if (i > 0) key.append(','); key.append(history.get(i)); }
        int[] cand = BOOK.get(key.toString());
        if (cand == null) return null;
        List<Integer> ok = new ArrayList<>();
        for (int v : cand) if (legal.contains(v)) ok.add(v);   // 합법 수만
        if (ok.isEmpty()) return null;
        return ok.get(ThreadLocalRandom.current().nextInt(ok.size()));
    }
}
