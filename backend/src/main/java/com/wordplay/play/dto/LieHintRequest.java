package com.wordplay.play.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record LieHintRequest(
        @NotNull @Min(0) @Max(2) Integer selectedLieIndex
) {}
