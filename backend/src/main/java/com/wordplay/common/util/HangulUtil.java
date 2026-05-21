package com.wordplay.common.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 한글 음절 분해 / WordGuess 자모 비교 유틸.
 *
 * 음절 = 초성(19) × 중성(21) × 종성(28) = 11,172
 * 유니코드 베이스 = 0xAC00 ('가')
 */
public class HangulUtil {

    private static final int HANGUL_BASE = 0xAC00;
    private static final int HANGUL_END = 0xD7A3;
    private static final int CHO_COUNT = 19;
    private static final int JUNG_COUNT = 21;
    private static final int JONG_COUNT = 28;

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
     * 한 음절을 [cho, jung, jong] 인덱스로 분해. 종성 없으면 jong = 0.
     */
    public static int[] decompose(char syllable) {
        if (!isHangulSyllable(syllable)) {
            throw new IllegalArgumentException("Not a Hangul syllable: " + syllable);
        }
        int code = syllable - HANGUL_BASE;
        int cho = code / (JUNG_COUNT * JONG_COUNT);
        int jung = (code % (JUNG_COUNT * JONG_COUNT)) / JONG_COUNT;
        int jong = code % JONG_COUNT;
        return new int[]{cho, jung, jong};
    }

    /**
     * WordGuess Wordle 표준 2-pass 비교.
     *
     * 1단계: 같은 위치/같은 자모 → H
     * 2단계: 정답에 남은 자모 풀(pool) 기반으로 M / S 판정
     *
     * 자모 분류는 같은 종류(초/중/종)끼리만 비교 (예: 초성 ㅅ은 중성 풀과 매칭 안 됨)
     */
    @SuppressWarnings("unchecked")
    public static List<SyllableResult> compareWords(String answer, String guess) {
        if (answer.length() != guess.length()) {
            throw new IllegalArgumentException("Length mismatch");
        }

        int n = answer.length();
        int[][] aJamo = new int[n][3];     // [i][cho, jung, jong]
        int[][] gJamo = new int[n][3];
        for (int i = 0; i < n; i++) {
            aJamo[i] = decompose(answer.charAt(i));
            gJamo[i] = decompose(guess.charAt(i));
        }

        // 결과 초기화: null = 미정
        String[][] res = new String[n][3];

        // ---- 1단계: Hit 판정 ----
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 3; k++) {
                if (aJamo[i][k] == 0 && gJamo[i][k] == 0 && k == 2) {
                    // 둘 다 종성 없음 → null (자모 자체가 없음)
                    res[i][k] = null;
                } else if (aJamo[i][k] == gJamo[i][k]) {
                    res[i][k] = "H";
                }
            }
        }

        // ---- 2단계: pool 구성 (H로 매칭되지 않은 정답 자모, 종류별로 분리) ----
        // pool[kind] = Map<jamoIdx, count>
        Map<Integer, Integer>[] pool = new Map[3];
        for (int k = 0; k < 3; k++) pool[k] = new HashMap<>();
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 3; k++) {
                if (res[i][k] != null && res[i][k].equals("H")) continue;
                if (k == 2 && aJamo[i][k] == 0) continue;  // 종성 없음
                pool[k].merge(aJamo[i][k], 1, Integer::sum);
            }
        }

        // ---- 3단계: Move / Skip 판정 ----
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 3; k++) {
                if (res[i][k] != null) continue;  // 이미 H 또는 null
                if (k == 2 && gJamo[i][k] == 0) {
                    // 추측에 종성 없음 → null
                    res[i][k] = null;
                    continue;
                }
                int jamo = gJamo[i][k];
                Integer cnt = pool[k].get(jamo);
                if (cnt != null && cnt > 0) {
                    res[i][k] = "M";
                    pool[k].merge(jamo, -1, Integer::sum);
                } else {
                    res[i][k] = "S";
                }
            }
        }

        // ---- 결과 조립 ----
        List<SyllableResult> output = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            output.add(new SyllableResult(
                    String.valueOf(guess.charAt(i)),
                    res[i][0], res[i][1], res[i][2]
            ));
        }
        return output;
    }

    public record SyllableResult(String syllable, String cho, String jung, String jong) {}
}
