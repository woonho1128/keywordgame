package com.wordplay.jobmafia.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 직업 마피아 방 생성 요청.
 * 각 직업은 최소~최대 범위로 지정하고, 시작 시 범위 안에서 랜덤으로 인원이 정해진다.
 * (예: psychoMin=0, psychoMax=1 → 정신병자가 나올 수도, 안 나올 수도)
 */
public record NewJobMafiaRequest(
        @NotBlank @Size(max = 16) String nick,
        Integer nightSec,
        Integer discussSec,
        Integer voteSec,
        Integer mafiaMin,
        Integer mafiaMax,
        Integer psychoMin,
        Integer psychoMax,
        Integer attentionMin,
        Integer attentionMax
) {}
