package com.wordplay.quiz.dto;

import java.util.List;
import java.util.Map;

/**
 * 퀴즈 상태 응답.
 *
 * <p>정답은 절대 내려보내지 않는다 — 공개 단계(lastReveal)에서만 실린다. 남이 무엇을
 * 썼는지도 그때 함께 보여준다(진행 중에는 답했는지 여부만).
 */
public record QuizState(
        String phase,                 // LOBBY / ASKING / REVEAL / ENDED
        String mode,                  // CLASSIC(문제 수 고정) / SPRINT(시간 제한 무제한)
        int level,                    // 난이도 1~10
        int totalRounds,              // CLASSIC 전체 문제 수. SPRINT면 0
        int round,                    // 지금 몇 번째 문제인지(1-based). 시작 전 0
        int questionSec,
        int limitSec,                 // SPRINT 제한시간(초)
        boolean isHost,
        boolean joined,
        List<PlayerView> players,
        String topic,                 // 이번 문제 주제
        String kind,                  // CHOICE / TEXT
        String question,
        List<String> choices,         // 객관식 보기. 주관식이면 빈 목록
        String hint,                  // 주관식 초성 힌트. 객관식이면 null
        String myAnswer,              // 내가 낸 답(객관식은 보기 번호). 아직이면 null
        boolean myAnswerRight,
        boolean canAnswer,            // 지금 답을 낼 수 있는가
        Reveal lastReveal,            // 정답 공개(REVEAL 단계에서만)
        MyLast myLast,                // SPRINT: 직전에 푼 문제 결과(짧게 보여준다). 없으면 null
        List<String> log,
        long deadline,
        long serverNow
) {
    public record PlayerView(int seat, String name, boolean host, boolean me, boolean left,
                             int score, int correct, int streak, int bestStreak, boolean answered,
                             int wrong, int skipped, int solved) {}

    /** SPRINT에서 직전 문제의 결과. right=null이면 넘긴 것. */
    public record MyLast(Boolean right, String answer, String explain) {}

    /**
     * 정답 공개.
     *
     * @param rightSeats 맞힌 사람들
     * @param given      좌석 -> 그 사람이 낸 답(사람이 읽을 형태)
     */
    public record Reveal(int round, String answer, String explain,
                         List<Integer> rightSeats, Map<Integer, String> given) {}

    /** 방이 사라졌을 때. */
    public static QuizState notFound(long now) {
        return new QuizState("NONE", "CLASSIC", 5, 0, 0, 20, 120, false, false,
                List.of(), null, null, null, List.of(), null, null, false, false,
                null, null, List.of(), 0, now);
    }
}
