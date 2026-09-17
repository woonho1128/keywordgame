package com.wordplay.mojo.dto;

/** 모죠 방 생성 요청. doublePile: 이중 버림더미 변형 규칙 사용 여부. */
public record NewMojoRequest(String nick, boolean doublePile) {}
