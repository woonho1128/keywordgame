package com.wordplay.lexio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 렉시오 방 생성 요청. */
public record NewLexioRequest(
        @NotBlank @Size(max = 16) String nick,
        String theme,       // BLACK / WHITE
        String scoreMode,   // SINGLE / ACCUMULATE
        Integer turnSec     // 차례 제한시간(초)
) {}
