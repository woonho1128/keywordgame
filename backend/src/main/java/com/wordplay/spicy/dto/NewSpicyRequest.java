package com.wordplay.spicy.dto;

/**
 * 스파이시 방 생성 요청.
 * challengeSec: 도전 대기 시간(초). handPenalty: 손에 남은 카드 1장당 -1점 룰 사용 여부.
 */
public record NewSpicyRequest(String nick, Integer challengeSec, Boolean handPenalty) {}
