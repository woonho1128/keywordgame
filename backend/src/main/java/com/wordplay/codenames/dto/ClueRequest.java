package com.wordplay.codenames.dto;

/** 스파이마스터 힌트: 단어 + 숫자. */
public record ClueRequest(String word, int number) {}
