package com.wordplay.sixnimmt.dto;

/** 방 생성 요청. endMode: POINTS(66점) / HANDS(N판). targetHands: HANDS일 때 판 수. */
public record NewSixNimmtRequest(String nick, String endMode, Integer targetHands) {}
