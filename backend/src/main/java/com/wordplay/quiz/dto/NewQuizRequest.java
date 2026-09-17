package com.wordplay.quiz.dto;

/**
 * 퀴즈 방 생성 요청.
 *
 * @param level       난이도 1~10
 * @param rounds      문제 수(CLASSIC)
 * @param questionSec 문제당 제한시간(초, CLASSIC)
 * @param mode        CLASSIC(문제 수 고정) / SPRINT(시간 제한 무제한 배틀)
 * @param limitSec    전체 제한시간(초, SPRINT)
 */
public record NewQuizRequest(String nick, Integer level, Integer rounds, Integer questionSec,
                             String mode, Integer limitSec) {}
