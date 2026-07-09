package com.wordplay.mafia.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 마피아 토론 채팅 요청. */
public record ChatRequest(
        @NotBlank @Size(max = 200) String text
) {}
