package com.wordplay.tetris.dto;

/** 배틀 방 생성 요청. format: DUEL(1v1) / ROYALE(배틀로얄). */
public record NewBattleRequest(String nick, String format) {}
