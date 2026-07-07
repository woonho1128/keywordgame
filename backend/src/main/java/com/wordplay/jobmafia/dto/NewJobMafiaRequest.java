package com.wordplay.jobmafia.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 직업 마피아 방 생성 요청. 특수직업 포함 여부/타이머는 선택. */
public record NewJobMafiaRequest(
        @NotBlank @Size(max = 16) String nick,
        Integer nightSec,
        Integer discussSec,
        Integer voteSec,
        Integer mafiaCount,
        Boolean includePsycho,   // 정신병자 포함
        Boolean includeAttention // 관종 포함
) {}
