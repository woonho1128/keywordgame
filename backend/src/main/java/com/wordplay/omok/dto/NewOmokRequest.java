package com.wordplay.omok.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 오목 방 생성 요청. */
public record NewOmokRequest(
        @NotBlank @Size(max = 16) String nick,
        String hostColor,   // BLACK / WHITE
        String rule         // FREE / RENJU
) {}
