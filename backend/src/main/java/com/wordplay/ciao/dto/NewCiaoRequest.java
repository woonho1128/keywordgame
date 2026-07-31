package com.wordplay.ciao.dto;

/** 차오차오 방 생성 요청. challengeSec: 선언 후 의심 대기 시간(초). */
public record NewCiaoRequest(String nick, Integer challengeSec) {}
