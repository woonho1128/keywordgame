package com.wordplay.horserace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 경마 방 생성 요청. */
public record NewHorseRaceRequest(
        @NotBlank @Size(max = 16) String nick,
        String token,        // 계정 토큰(있으면 계정 플레이), null이면 게스트
        String raceType,     // BASIC / SPECIAL (v1은 BASIC)
        String oddsMode,     // FIXED / PARIMUTUEL (v1은 FIXED)
        Integer buyIn,       // 게스트 시작 칩
        Integer betSec,      // 배팅 제한시간(초)
        Integer horseCount,  // 출전 두수(기본 9)
        Integer autoEndRounds // 선택: N판 후 자동 종료
) {}
