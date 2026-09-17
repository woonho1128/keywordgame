package com.wordplay.saju.dto;

import com.wordplay.saju.domain.CompatType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** 궁합 조회 요청 — 두 사람 */
public record CompatRequest(

        @NotNull(message = "궁합 종류를 선택해주세요")
        CompatType compatType,

        @Valid
        @NotNull(message = "첫 번째 사람 정보를 입력해주세요")
        PersonInput personA,

        @Valid
        @NotNull(message = "두 번째 사람 정보를 입력해주세요")
        PersonInput personB
) {}
