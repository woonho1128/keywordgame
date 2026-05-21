package com.wordplay.game.dto;

import com.wordplay.game.entity.GameType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateGameRequest(
        @NotNull GameType gameType,
        @NotBlank @Size(min = 1, max = 20) String answerWord,
        @Size(max = 500) String hintText,
        @Size(max = 50) String creatorNick,
        Boolean isPublic
) {}
