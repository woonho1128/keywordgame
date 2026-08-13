package com.wordplay.quiz;

import java.util.List;

/**
 * 상식 퀴즈 한 문제.
 *
 * <p>{@link Kind#CHOICE}는 보기 4개 중 하나를 고르고, {@link Kind#TEXT}는 답을 직접 쓴다.
 * 주관식은 표기가 여러 가지라(이순신/충무공, 띄어쓰기, 영문) 허용 답을 목록으로 받는다.
 *
 * @param answers  주관식 허용 답. 첫 번째가 대표 표기(정답 공개에 쓴다).
 */
public record QuizQuestion(
        String id,
        String topic,
        int level,
        Kind kind,
        String question,
        List<String> choices,
        int answerIndex,
        List<String> answers,
        String explain
) {
    public enum Kind { CHOICE, TEXT }

    /** 정답 대표 표기. 화면에 "정답: ○○"으로 보여줄 값. */
    public String answerLabel() {
        if (kind == Kind.CHOICE)
            return answerIndex >= 0 && answerIndex < choices.size() ? choices.get(answerIndex) : "?";
        return answers.isEmpty() ? "?" : answers.get(0);
    }

    /** 주관식 초성 힌트. 예: "이순신" → "ㅇㅅㅅ". 한글이 아닌 글자는 그대로 둔다. */
    public String hint() {
        if (kind != Kind.TEXT) return null;
        return chosung(answerLabel());
    }

    /** 이 답이 맞는가. 대소문자·공백·괄호·문장부호 차이는 무시한다. */
    public boolean accepts(String given) {
        String g = normalize(given);
        if (g.isEmpty()) return false;
        if (kind == Kind.CHOICE)
            return answerIndex >= 0 && answerIndex < choices.size()
                    && normalize(choices.get(answerIndex)).equals(g);
        for (String a : answers) if (normalize(a).equals(g)) return true;
        return false;
    }

    // ── 문자열 정리 ──

    private static final int HANGUL_BASE = 0xAC00, JUNG = 21, JONG = 28;
    private static final String[] CHO = {
            "ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ",
            "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ" };

    /**
     * 초성만 뽑는다.
     *
     * <p>{@code HangulUtil.decompose}는 겹자모를 낱자로 쪼개(ㄲ → ㄱ,ㄱ) 힌트 표기에
     * 맞지 않으므로 여기서 따로 계산한다.
     */
    static String chosung(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= HANGUL_BASE && c <= 0xD7A3) sb.append(CHO[(c - HANGUL_BASE) / (JUNG * JONG)]);
            else if (c == ' ') sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 비교용 정규화.
     *
     * <p>"이 순신", "이순신.", "이순신(李舜臣)"을 모두 같게 본다. 정답인데 오답 처리되는
     * 억울함이 주관식의 가장 큰 불만이라 넉넉하게 깎는다.
     */
    static String normalize(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase();
        t = t.replaceAll("\\([^)]*\\)", "");              // 괄호 보충 설명 제거
        t = t.replaceAll("[\\s.,!?~·'\"\\-_/]", "");      // 공백·문장부호 제거
        return t;
    }
}
