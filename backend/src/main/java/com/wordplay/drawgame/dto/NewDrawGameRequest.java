package com.wordplay.drawgame.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NewDrawGameRequest(
        @NotBlank @Size(max = 16) String nick,
        String mode,        // GARTIC / CATCHMIND
        String topicMode,   // FREE / RANDOM
        Integer writeSec,   // 갈틱폰: 문장 시간
        Integer drawSec,    // 갈틱폰: 그림 시간
        Integer roundSec    // 캐치마인드: 라운드 시간
) {}
