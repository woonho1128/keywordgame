package com.wordplay.othello.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 오델로 방 생성 요청. */
public record NewOthelloRequest(
        @NotBlank @Size(max = 16) String nick,
        String hostColor,   // BLACK / WHITE (방장 색)
        Integer turnSec     // 차례 제한시간(초)
) {}
