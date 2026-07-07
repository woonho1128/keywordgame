package com.wordplay.common.dto;

/** 방 목록 항목. status: WAITING(모집중) / PLAYING(진행중) / ENDED(종료). */
public record RoomSummary(String code, String status, int playerCount, String host) {}
