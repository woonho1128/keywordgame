package com.wordplay.quiz.dto;

/**
 * 시간 배틀 순위 한 줄.
 *
 * @param rank    1부터
 * @param score   점수(순위 기준). 맞으면 +, 틀리거나 넘기면 -
 * @param correct 맞힌 개수
 * @param solved  푼 문제 수(정답+오답+넘김)
 */
public record QuizRankRow(int rank, String nick, int score, int correct, int solved, long at) {}
