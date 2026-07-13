package com.wordplay.coup.dto;

import java.util.List;

/** 액션/반응/카드상실/교환 통합 요청 바디. */
public record CoupActionRequest(
        String action,      // act: INCOME/FOREIGN_AID/COUP/TAX/ASSASSINATE/STEAL/EXCHANGE
        Integer target,     // act 대상 seat
        String decision,    // respond: PASS/CHALLENGE/BLOCK
        String blockCard,   // respond BLOCK 시 주장 카드
        Integer cardIndex,  // lose-card
        List<Integer> keep  // exchange 유지 인덱스
) {}
