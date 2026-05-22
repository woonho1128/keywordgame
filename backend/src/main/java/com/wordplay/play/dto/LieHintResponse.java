package com.wordplay.play.dto;

public record LieHintResponse(
        Boolean lieCorrect,
        String status,
        String revealedAnswer,
        Integer revealedLieIndex,
        Integer timeSpentSec
) {}
