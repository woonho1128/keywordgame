package com.wordplay.sherlock.dto;

/** 셜록13 방 생성 요청. turnSec: 한 턴(질문/지목) 제한시간(초). */
public record NewSherlockRequest(String nick, Integer turnSec) {}
