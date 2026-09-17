package com.wordplay.mafia.dto;

/** 밤 행동/투표 대상 좌석(1-based). 기권/미선택은 -1. */
public record TargetRequest(int target) {}
