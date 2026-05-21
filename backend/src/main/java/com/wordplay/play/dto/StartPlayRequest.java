package com.wordplay.play.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StartPlayRequest(
        @NotBlank @Size(min = 1, max = 50) String playerNick
) {}
