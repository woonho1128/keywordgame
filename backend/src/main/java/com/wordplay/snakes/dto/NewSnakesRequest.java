package com.wordplay.snakes.dto;

/** 뱀과 사다리 방 생성 요청. turnSec: 자기 차례 제한시간(초). 0이면 제한 없음. */
public record NewSnakesRequest(String nick, Integer turnSec) {}
