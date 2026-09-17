package com.wordplay.mafia.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 마피아 참가 요청(닉네임). */
public record JoinRequest(
        @NotBlank @Size(max = 16) String nick
) {}
