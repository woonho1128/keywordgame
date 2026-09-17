package com.wordplay.spyfall.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 새 스파이폴 판 생성 요청. */
public record NewGameRequest(
        @Min(3) @Max(12) int playerCount,
        @Min(1) @Max(6) int spyCount
) {}
