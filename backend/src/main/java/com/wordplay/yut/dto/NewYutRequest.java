package com.wordplay.yut.dto;

/** 윷놀이 방 생성 요청. teamMode: 팀전(2:2), backDo: 백도 사용. */
public record NewYutRequest(String nick, boolean teamMode, boolean backDo) {}
