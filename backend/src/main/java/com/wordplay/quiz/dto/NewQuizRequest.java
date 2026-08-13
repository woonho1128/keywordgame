package com.wordplay.quiz.dto;

/**
 * 퀴즈 방 생성 요청.
 *
 * @param level      난이도 1~10
 * @param rounds     문제 수
 * @param questionSec 문제당 제한시간(초)
 */
public record NewQuizRequest(String nick, Integer level, Integer rounds, Integer questionSec) {}
