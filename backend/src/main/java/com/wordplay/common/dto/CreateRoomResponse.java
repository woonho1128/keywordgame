package com.wordplay.common.dto;

/** 방 생성 응답: 새 방 코드 + 초기 상태. */
public record CreateRoomResponse<T>(String roomCode, T state) {}
