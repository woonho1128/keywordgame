package com.wordplay.halligalli.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NewHalliGalliRequest(
        @NotBlank @Size(max = 16) String nick
) {}
