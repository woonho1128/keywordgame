package com.wordplay.rummikub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 루미큐브 방 생성 요청. */
public record NewRummikubRequest(
        @NotBlank @Size(max = 16) String nick,
        Integer turnSec   // 차례 제한시간(초). 미지정 시 기본 60, 30~300 범위로 클램프
) {}
