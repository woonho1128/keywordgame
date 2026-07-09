package com.wordplay.drawgame.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NewDrawGameRequest(
        @NotBlank @Size(max = 16) String nick,
        String mode,        // GARTIC / CATCHMIND
        String topicMode,   // FREE / RANDOM
        Integer writeSec,
        Integer drawSec
) {}
