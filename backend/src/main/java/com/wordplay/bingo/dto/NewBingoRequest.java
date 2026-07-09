package com.wordplay.bingo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NewBingoRequest(
        @NotBlank @Size(max = 16) String nick,
        Integer size,     // 3 / 4 / 5
        Integer target    // 승리 줄 수
) {}
