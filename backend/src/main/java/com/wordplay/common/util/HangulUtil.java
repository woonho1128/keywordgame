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

    // 초성 — 쌍자음은 같은 자모 2개로 분해 (꼬들 표준)
    private static final String[][] CHO = {
            {"ㄱ"},{"ㄱ","ㄱ"},{"ㄴ"},{"ㄷ"},{"ㄷ","ㄷ"},
            {"ㄹ"},{"ㅁ"},{"ㅂ"},{"ㅂ","ㅂ"},{"ㅅ"},
            {"ㅅ","ㅅ"},{"ㅇ"},{"ㅈ"},{"ㅈ","ㅈ"},{"ㅊ"},
            {"ㅋ"},{"ㅌ"},{"ㅍ"},{"ㅎ"}
    };

    // 중성 — 이중모음/복합모음 모두 기본 자모로 분해
    //   ㅐ=ㅏㅣ  ㅒ=ㅑㅣ  ㅔ=ㅓㅣ  ㅖ=ㅕㅣ
    //   ㅘ=ㅗㅏ  ㅙ=ㅗㅏㅣ  ㅚ=ㅗㅣ
    //   ㅝ=ㅜㅓ  ㅞ=ㅜㅓㅣ  ㅟ=ㅜㅣ
    //   ㅢ=ㅡㅣ
    private static final String[][] JUNG = {
            {"ㅏ"},{"ㅏ","ㅣ"},{"ㅑ"},{"ㅑ","ㅣ"},
            {"ㅓ"},{"ㅓ","ㅣ"},{"ㅕ"},{"ㅕ","ㅣ"},
            {"ㅗ"},{"ㅗ","ㅏ"},{"ㅗ","ㅏ","ㅣ"},{"ㅗ","ㅣ"},
            {"ㅛ"},
            {"ㅜ"},{"ㅜ","ㅓ"},{"ㅜ","ㅓ","ㅣ"},{"ㅜ","ㅣ"},
            {"ㅠ"},{"ㅡ"},{"ㅡ","ㅣ"},{"ㅣ"}
    };

    // 종성 — 쌍자음/겹받침 모두 기본 자모로 분해
    private static final String[][] JONG = {
            {},
            {"ㄱ"},{"ㄱ","ㄱ"},{"ㄱ","ㅅ"},
            {"ㄴ"},{"ㄴ","ㅈ"},{"ㄴ","ㅎ"},
            {"ㄷ"},
            {"ㄹ"},{"ㄹ","ㄱ"},{"ㄹ","ㅁ"},{"ㄹ","ㅂ"},{"ㄹ","ㅅ"},
            {"ㄹ","ㅌ"},{"ㄹ","ㅍ"},{"ㄹ","ㅎ"},
            {"ㅁ"},{"ㅂ"},{"ㅂ","ㅅ"},{"ㅅ"},{"ㅅ","ㅅ"},
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

        List<Jamo> out = new ArrayList<>(6);
        for (String j : CHO[cho]) out.add(new Jamo(j, Kind.CHO));
        for (String j : JUNG[jung]) out.add(new Jamo(j, Kind.JUNG));
        if (jong > 0) {
            for (String j : JONG[jong]) out.add(new Jamo(j, Kind.JONG));
        }
        return out;
    }

    /**
     * 단어 전체를 평탄화된(flat) 자모 리스트로 분해.
     * 음절 경계 무시. 비교/카운팅에 사용.
     */
    public static List<Jamo> decomposeFlat(String word) {
        List<Jamo> out = new ArrayList<>(word.length() * 3);
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (isHangulSyllable(c)) out.addAll(decompose(c));
        }
        return out;
    }

    /** 단어의 총 자모 수 (꼬들 기준). */
    public static int countJamos(String word) {
        return decomposeFlat(word).size();
    }

    /**
     * 꼬들 표준 2-pass 비교 — 자모 수 일치만 요구하고 음절 수는 자유.
     *
     * 매칭 규칙:
     *   - 정답/추측을 평탄화 (flat jamo list)
     *   - 같은 kind(CHO/JUNG/JONG) 안에서만 비교
     *   - 1단계: 같은 kind 안 같은 position에 같은 jamo → H
     *   - 2단계: 남은 자모는 같은 kind pool에서 매칭하면 M, 없으면 S
     *   - 결과는 추측의 음절 구조에 맞춰 그룹화하여 반환 (시각화용)
     */
    public static List<SyllableResult> compareWords(String answer, String guess) {
        List<Jamo> aFlat = decomposeFlat(answer);
        List<Jamo> gFlat = decomposeFlat(guess);

        if (aFlat.size() != gFlat.size()) {
            throw new IllegalArgumentException(
                    "Jamo count mismatch: answer=" + aFlat.size() + " guess=" + gFlat.size());
        }

        // kind별 인덱스 묶기
        Map<Kind, List<Integer>> aIdx = groupByKind(aFlat);
        Map<Kind, List<Integer>> gIdx = groupByKind(gFlat);

        String[] gMarks = new String[gFlat.size()];
        boolean[] aTaken = new boolean[aFlat.size()];

        // ---- 1단계: Hit ----
        for (Kind k : Kind.values()) {
            List<Integer> a = aIdx.getOrDefault(k, List.of());
            List<Integer> g = gIdx.getOrDefault(k, List.of());
            int len = Math.min(a.size(), g.size());
            for (int p = 0; p < len; p++) {
                int ai = a.get(p), gi = g.get(p);
                if (aFlat.get(ai).jamo().equals(gFlat.get(gi).jamo())) {
                    gMarks[gi] = "H";
                    aTaken[ai] = true;
                }
            }
        }

        // ---- 2단계: Pool 기반 Move/Skip ----
        Map<Kind, Map<String, Integer>> pool = new HashMap<>();
        for (Kind k : Kind.values()) pool.put(k, new HashMap<>());
        for (int ai = 0; ai < aFlat.size(); ai++) {
            if (aTaken[ai]) continue;
            Jamo j = aFlat.get(ai);
            pool.get(j.kind()).merge(j.jamo(), 1, Integer::sum);
        }
        for (int gi = 0; gi < gFlat.size(); gi++) {
            if (gMarks[gi] != null) continue;
            Jamo j = gFlat.get(gi);
            Map<String, Integer> kindPool = pool.get(j.kind());
            Integer cnt = kindPool.get(j.jamo());
            if (cnt != null && cnt > 0) {
                gMarks[gi] = "M";
                kindPool.merge(j.jamo(), -1, Integer::sum);
            } else {
                gMarks[gi] = "S";
            }
        }

        // ---- 결과를 추측 음절 단위로 묶어서 반환 ----
        List<SyllableResult> out = new ArrayList<>();
        int idx = 0;
        for (int i = 0; i < guess.length(); i++) {
            char c = guess.charAt(i);
            if (!isHangulSyllable(c)) continue;
            List<Jamo> sylJamos = decompose(c);
            List<JamoMark> marks = new ArrayList<>(sylJamos.size());
            for (Jamo j : sylJamos) {
                marks.add(new JamoMark(j.jamo(), j.kind(), gMarks[idx++]));
            }
            out.add(new SyllableResult(String.valueOf(c), marks));
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
