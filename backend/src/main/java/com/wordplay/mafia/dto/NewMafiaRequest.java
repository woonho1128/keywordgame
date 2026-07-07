package com.wordplay.mafia.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 마피아 방 생성 요청. 타이머/마피아 수는 선택(미지정 시 기본값).
 */
public record NewMafiaRequest(
        @NotBlank @Size(max = 16) String nick,
        Integer nightSec,
        Integer discussSec,
        Integer voteSec,
        Integer mafiaCount,
        Boolean revealOnDeath
) {}
