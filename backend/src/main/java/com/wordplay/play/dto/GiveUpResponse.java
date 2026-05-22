package com.wordplay.play.dto;

public record GiveUpResponse(
        String answerWord,
        Integer totalAttempts,
        Integer revealedLieIndex
) {}
