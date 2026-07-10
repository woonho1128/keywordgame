package com.wordplay.bingo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NewBingoRequest(
        @NotBlank @Size(max = 16) String nick,
        Integer size,     // 3 / 4 / 5
        Integer target,   // 승리 줄 수
        String mode       // AUTO(봇 자동) / TURN(번갈아 지목), 미지정 시 AUTO
) {}
