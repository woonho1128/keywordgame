package com.wordplay.monopoly.dto;

/** 부루마블 방 생성 요청. teamMode=true면 2:2 팀전(4명 필요). */
public record NewMonopolyRequest(String nick, boolean teamMode) {}
