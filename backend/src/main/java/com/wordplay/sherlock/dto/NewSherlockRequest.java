package com.wordplay.sherlock.dto;

/** 셜록13 방 생성 요청. turnSec: 턴 제한(초). memoryMode: 정통 기억 모드(단서 로그 미보존). */
public record NewSherlockRequest(String nick, Integer turnSec, Boolean memoryMode) {}
