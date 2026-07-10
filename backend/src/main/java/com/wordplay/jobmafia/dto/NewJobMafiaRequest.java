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
        Integer attentionMax,
        Integer thiefMin,
        Integer thiefMax,
        // 중립(관종·도적꾼) 통합 설정: true면 아래 총 인원 범위에서 랜덤으로 뽑고,
        // 어떤 중립 직업이 나올지는 게임이 랜덤으로 정한다(관종/도적꾼 개별 설정 무시).
        Boolean neutralGrouped,
        Integer neutralMin,
        Integer neutralMax,
        // 마피아 총원 안에서 배정되는 특수 마피아 인원
        Integer mafiaCopMin,     // 경찰마피아(조사/살해 택1)
        Integer mafiaCopMax,
        Integer mafiaShadowMin,  // 그림자마피아(살해 시 정체 은폐)
        Integer mafiaShadowMax
) {}
