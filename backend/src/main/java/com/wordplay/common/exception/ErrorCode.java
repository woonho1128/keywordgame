package com.wordplay.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    GAME_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 게임을 찾을 수 없습니다"),
    GAME_ALREADY_FINISHED(HttpStatus.BAD_REQUEST, "이미 종료된 게임입니다"),
    INVALID_WORD_LENGTH(HttpStatus.BAD_REQUEST, "정답과 자모 수가 다릅니다"),
    INVALID_HANGUL(HttpStatus.BAD_REQUEST, "한글 음절만 입력 가능합니다"),
    WORD_NOT_IN_DICTIONARY(HttpStatus.BAD_REQUEST, "사전에 없는 단어입니다"),
    SESSION_NOT_FOUND(HttpStatus.UNAUTHORIZED, "세션이 없습니다. 게임을 다시 시작해주세요"),
    DUPLICATE_GUESS(HttpStatus.BAD_REQUEST, "이미 시도한 단어입니다"),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }
}
