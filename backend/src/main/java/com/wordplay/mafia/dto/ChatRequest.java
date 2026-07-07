package com.wordplay.mafia.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 마피아 밤 채팅 메시지. */
public record ChatRequest(
        @NotBlank @Size(max = 200) String text
) {}
