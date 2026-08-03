package com.wordplay.feedback.dto;

import java.util.List;

/**
 * 건의 접수 요청. category: BUG/IDEA/GAME/ETC. contact는 선택(답장용).
 * images는 메일에 첨부만 하고 서버에 보관하지 않는다.
 */
public record FeedbackRequest(String category, String nickname, String contact, String message, String page,
                              List<Image> images) {

    /** content: base64(데이터 URL 접두사 없이). */
    public record Image(String filename, String content) {}
}
