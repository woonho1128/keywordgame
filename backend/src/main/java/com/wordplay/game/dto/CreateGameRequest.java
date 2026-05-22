package com.wordplay.game.dto;

import com.wordplay.game.entity.GameType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateGameRequest(
        @NotNull GameType gameType,
        @NotBlank @Size(min = 1, max = 60) String title,
        @NotBlank @Size(min = 1, max = 20) String answerWord,
        @Size(max = 500) String hintText,
        @NotBlank @Size(min = 1, max = 50) String creatorNick,
        Boolean isPublic,
        List<@NotBlank @Size(min = 1, max = 120) String> lieHints,
        Integer lieIndex
) {}
