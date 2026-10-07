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

    /** 단어의 음절(글자) 수 — 한글 음절 문자만 센다. */
    public static int countSyllables(String word) {
        if (word == null) return 0;
        int n = 0;
        for (int i = 0; i < word.length(); i++) {
            if (isHangulSyllable(word.charAt(i))) n++;
        }
        return n;
    }

    /**
     * 꼬들 표준 2-pass 비교 — 자모 수 일치만 요구하고 음절 수는 자유.
     *
     * 매칭 규칙:
     *   - 정답/추측을 평탄화 (flat jamo list) — 입력 칸에 보이는 자모 한 줄과 같은 순서
     *   - 1단계: 같은 칸(인덱스)에 같은 jamo → H. 초성/받침 역할은 따지지 않는다
     *     (예: 정답 사과 ㅅㅏ[ㄱ]ㅗㅏ / 추측 석수 ㅅㅓ[ㄱ]ㅅㅜ → 셋째 칸 ㄱ은 H)
     *   - 예외: 자모 줄이 통째로 같은데 단어가 다르면(쌍자음이 받침으로 갈라진 아까/악가 등)
     *     역할이 다른 칸은 H에서 빼서 오답이 전부 초록으로 보이지 않게 한다
     *   - 2단계: H로 쓰이지 않은 정답 자모 pool(글자 기준)에 있으면 M, 없으면 S
     *     (S는 "정답에 남은 그 자모가 없음"을 뜻한다)
     *   - 결과는 추측의 음절 구조에 맞춰 그룹화하여 반환 (시각화용)
     */
    public static List<SyllableResult> compareWords(String answer, String guess) {
        List<Jamo> aFlat = decomposeFlat(answer);
        List<Jamo> gFlat = decomposeFlat(guess);

        if (aFlat.size() != gFlat.size()) {
            throw new IllegalArgumentException(
                    "Jamo count mismatch: answer=" + aFlat.size() + " guess=" + gFlat.size());
        }

        int n = gFlat.size();
        String[] gMarks = new String[n];
        boolean[] aTaken = new boolean[n];

        // ---- 1단계: Hit ----
        boolean[] hit = new boolean[n];
        boolean sameLine = true;
        for (int i = 0; i < n; i++) {
            hit[i] = aFlat.get(i).jamo().equals(gFlat.get(i).jamo());
            sameLine &= hit[i];
        }
        if (sameLine && !answer.equals(guess)) {
            for (int i = 0; i < n; i++) {
                if (aFlat.get(i).kind() != gFlat.get(i).kind()) hit[i] = false;
            }
        }
        for (int i = 0; i < n; i++) {
            if (hit[i]) {
                gMarks[i] = "H";
                aTaken[i] = true;
            }
        }

        // ---- 2단계: Pool 기반 Move/Skip ----
        Map<String, Integer> pool = new HashMap<>();
        for (int ai = 0; ai < aFlat.size(); ai++) {
            if (!aTaken[ai]) pool.merge(aFlat.get(ai).jamo(), 1, Integer::sum);
        }
        for (int gi = 0; gi < gFlat.size(); gi++) {
            if (gMarks[gi] != null) continue;
            String jamo = gFlat.get(gi).jamo();
            int cnt = pool.getOrDefault(jamo, 0);
            if (cnt > 0) {
                gMarks[gi] = "M";
                pool.put(jamo, cnt - 1);
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
}
