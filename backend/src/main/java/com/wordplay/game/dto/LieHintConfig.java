package com.wordplay.game.dto;

import java.util.List;

public record LieHintConfig(
        List<String> hints,
        Integer lieIndex
) {}
