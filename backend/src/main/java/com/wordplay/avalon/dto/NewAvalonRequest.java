package com.wordplay.avalon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 아발론 방 생성 요청. 선택 직업 포함 여부(토글). */
public record NewAvalonRequest(
        @NotBlank @Size(max = 16) String nick,
        Boolean includePercivalMorgana, // 퍼시발 + 모르가나(쌍)
        Boolean includeMordred,         // 모드레드(멀린이 못 봄)
        Boolean includeOberon           // 오베론(동료 악을 모름)
) {}
