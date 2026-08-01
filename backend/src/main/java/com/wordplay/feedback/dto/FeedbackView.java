package com.wordplay.feedback.dto;

/** 관리자 조회용 뷰. */
public record FeedbackView(Long id, String category, String nickname, String contact,
                           String message, String page, boolean mailSent, String createdAt) {}
