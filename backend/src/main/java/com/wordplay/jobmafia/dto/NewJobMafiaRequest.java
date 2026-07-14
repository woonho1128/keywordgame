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
        Integer mafiaShadowMax,
        // 시민 능력직
        Integer observerMin,     // 관찰자(대상의 밤 지목 확인)
        Integer observerMax,
        Integer blockerMin,      // 봉쇄자(대상 밤 능력 무효)
        Integer blockerMax,
        // 능력마피아(마피아 총원 안에서 배정, 능력/킬 택1)
        Integer mafiaObserverMin, // 관찰자마피아
        Integer mafiaObserverMax,
        Integer mafiaBlockerMin,  // 봉쇄자마피아
        Integer mafiaBlockerMax,
        // 능력마피아 독립 킬 모드: true면 능력마피아(살해 모드)·그림자마피아가 자기 표적을 각자 처치
        // (밤에 여러 명 사망 가능). false(기본)면 모든 마피아가 다수결로 1명만 처치.
        Boolean abilityIndependentKill
) {}
