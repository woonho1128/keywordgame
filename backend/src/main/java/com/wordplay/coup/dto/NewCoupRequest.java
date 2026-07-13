package com.wordplay.coup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 쿠 방 생성 요청. */
public record NewCoupRequest(
        @NotBlank @Size(max = 16) String nick,
        Integer reactionSec   // 8 / 15 / 25
) {}
