package com.wordplay.feedback.dto;

/** 건의 접수 요청. category: BUG/IDEA/GAME/ETC. contact는 선택(답장용). */
public record FeedbackRequest(String category, String nickname, String contact, String message, String page) {}
