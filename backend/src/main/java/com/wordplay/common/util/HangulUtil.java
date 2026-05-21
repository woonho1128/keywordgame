package com.wordplay.common.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 한글 음절 분해 + 자모 비교.
 *
 * 음절 = 초성(19) × 중성(21) × 종성(28).
 * 복합 중성(ㅘ, ㅙ, ㅚ, ㅝ, ㅞ, ㅟ, ㅢ)과 복합 종성(ㄳ, ㄶ 등)은
 * 기본 자모로 분리해서 매칭한다 (꼬들 표준).
 */
public class HangulUtil {

    private static final int HANGUL_BASE = 0xAC00;
    private static final int HANGUL_END = 0xD7A3;
    private static final int CHO_COUNT = 19;
    private static final int JUNG_COUNT = 21;
    private static final int JONG_COUNT = 28;

    // 초성 문자
    private static final String[] CHO = {
            "ㄱ","ㄲ","ㄴ","ㄷ","ㄸ","ㄹ","ㅁ","ㅂ","ㅃ","ㅅ",
            "ㅆ","ㅇ","ㅈ","ㅉ","ㅊ","ㅋ","ㅌ","ㅍ","ㅎ"
    };

    // 중성 — null이면 단일, 배열이면 복합 분해
    private static final String[][] JUNG = {
            {"ㅏ"},{"ㅐ"},{"ㅑ"},{"ㅒ"},{"ㅓ"},{"ㅔ"},{"ㅕ"},{"ㅖ"},
            {"ㅗ"},{"ㅗ","ㅏ"},{"ㅗ","ㅐ"},{"ㅗ","ㅣ"},
            {"ㅛ"},
            {"ㅜ"},{"ㅜ","ㅓ"},{"ㅜ","ㅔ"},{"ㅜ","ㅣ"},
            {"ㅠ"},{"ㅡ"},{"ㅡ","ㅣ"},{"ㅣ"}
    };

    // 종성 — 인덱스 0은 종성 없음, 1~27 (복합 포함)
    private static final String[][] JONG = {
            {},
            {"ㄱ"},{"ㄲ"},{"ㄱ","ㅅ"},
            {"ㄴ"},{"ㄴ","ㅈ"},{"ㄴ","ㅎ"},
            {"ㄷ"},
            {"ㄹ"},{"ㄹ","ㄱ"},{"ㄹ","ㅁ"},{"ㄹ","ㅂ"},{"ㄹ","ㅅ"},
            {"ㄹ","ㅌ"},{"ㄹ","ㅍ"},{"ㄹ","ㅎ"},
            {"ㅁ"},{"ㅂ"},{"ㅂ","ㅅ"},{"ㅅ"},{"ㅆ"},
            {"ㅇ"},{"ㅈ"},{"ㅊ"},{"ㅋ"},{"ㅌ"},{"ㅍ"},{"ㅎ"}
    };

    public enum Kind { CHO, JUNG, JONG }

    public record Jamo(String jamo, Kind kind) {}
    public record JamoMark(String jamo, Kind kind, String mark) {}
    public record SyllableResult(String syllable, List<JamoMark> marks) {}

    private HangulUtil() {}

    public static boolean isHangulSyllable(char c) {
        return c >= HANGUL_BASE && c <= HANGUL_END;
    }

    public static boolean isAllHangulSyllables(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!isHangulSyllable(s.charAt(i))) return false;
        }
        return true;
    }

    /**
     * 한 음절을 기본 자모 리스트로 분해.
     * 예: '과' → [ㄱ(CHO), ㅗ(JUNG), ㅏ(JUNG)]
     *     '닭' → [ㄷ(CHO), ㅏ(JUNG), ㄹ(JONG), ㄱ(JONG)]
     */
    public static List<Jamo> decompose(char syllable) {
        if (!isHangulSyllable(syllable)) {
            throw new IllegalArgumentException("Not a Hangul syllable: " + syllable);
        }
        int code = syllable - HANGUL_BASE;
        int cho = code / (JUNG_COUNT * JONG_COUNT);
        int jung = (code % (JUNG_COUNT * JONG_COUNT)) / JONG_COUNT;
        int jong = code % JONG_COUNT;

        List<Jamo> out = new ArrayList<>(5);
        out.add(new Jamo(CHO[cho], Kind.CHO));
        for (String j : JUNG[jung]) out.add(new Jamo(j, Kind.JUNG));
        if (jong > 0) {
            for (String j : JONG[jong]) out.add(new Jamo(j, Kind.JONG));
        }
        return out;
    }

    /**
     * 꼬들/Wordle 표준 2-pass 비교.
     *
     * 매칭 규칙:
     *   - 음절 단위로 매칭 (음절 i끼리만 비교)
     *   - 같은 음절 내, 같은 kind(CHO/JUNG/JONG) 안에서만 비교
     *   - 1단계: 같은 kind 안 같은 position(0/1)에 같은 jamo → H
     *   - 2단계: 남은 자모는 같은 kind pool에서 매칭하면 M, 없으면 S
     */
    public static List<SyllableResult> compareWords(String answer, String guess) {
        if (answer.length() != guess.length()) {
            throw new IllegalArgumentException("Length mismatch");
        }
        int n = answer.length();
        List<SyllableResult> out = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            List<Jamo> aJamos = decompose(answer.charAt(i));
            List<Jamo> gJamos = decompose(guess.charAt(i));

            // kind별 인덱스 묶기
            Map<Kind, List<Integer>> aIdx = groupByKind(aJamos);
            Map<Kind, List<Integer>> gIdx = groupByKind(gJamos);

            String[] gMarks = new String[gJamos.size()];   // 추측 각 자모의 마크 (결과)
            boolean[] aTaken = new boolean[aJamos.size()]; // 정답에서 H로 소진된 자모 표시

            // ---- 1단계: Hit ----
            for (Kind k : Kind.values()) {
                List<Integer> a = aIdx.getOrDefault(k, List.of());
                List<Integer> g = gIdx.getOrDefault(k, List.of());
                int len = Math.min(a.size(), g.size());
                for (int p = 0; p < len; p++) {
                    int ai = a.get(p), gi = g.get(p);
                    if (aJamos.get(ai).jamo().equals(gJamos.get(gi).jamo())) {
                        gMarks[gi] = "H";
                        aTaken[ai] = true;
                    }
                }
            }

            // ---- 2단계: Move/Skip ----
            // kind별 pool 만들기 (H로 소진되지 않은 정답 자모)
            Map<Kind, Map<String, Integer>> pool = new HashMap<>();
            for (Kind k : Kind.values()) pool.put(k, new HashMap<>());
            for (int ai = 0; ai < aJamos.size(); ai++) {
                if (aTaken[ai]) continue;
                Jamo j = aJamos.get(ai);
                pool.get(j.kind()).merge(j.jamo(), 1, Integer::sum);
            }

            // 추측 자모 중 미정인 것들 처리
            for (int gi = 0; gi < gJamos.size(); gi++) {
                if (gMarks[gi] != null) continue;
                Jamo j = gJamos.get(gi);
                Map<String, Integer> kindPool = pool.get(j.kind());
                Integer cnt = kindPool.get(j.jamo());
                if (cnt != null && cnt > 0) {
                    gMarks[gi] = "M";
                    kindPool.merge(j.jamo(), -1, Integer::sum);
                } else {
                    gMarks[gi] = "S";
                }
            }

            // 결과 조립
            List<JamoMark> marks = new ArrayList<>(gJamos.size());
            for (int gi = 0; gi < gJamos.size(); gi++) {
                Jamo j = gJamos.get(gi);
                marks.add(new JamoMark(j.jamo(), j.kind(), gMarks[gi]));
            }
            out.add(new SyllableResult(String.valueOf(guess.charAt(i)), marks));
        }
        return out;
    }

    private static Map<Kind, List<Integer>> groupByKind(List<Jamo> jamos) {
        Map<Kind, List<Integer>> m = new HashMap<>();
        for (Kind k : Kind.values()) m.put(k, new ArrayList<>());
        for (int i = 0; i < jamos.size(); i++) {
            m.get(jamos.get(i).kind()).add(i);
        }
        return m;
    }
}
