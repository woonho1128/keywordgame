package com.wordplay.jobmafia;

import com.wordplay.jobmafia.JobMafiaService.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 능력마피아 공유 킬 vs 독립 킬 모드 검증. */
class JobMafiaKillModeTest {

    // 좌석: 0=일반마피아, 1=그림자마피아, 2=시민, 3=시민, 4=의사
    private JobMafiaService setup() {
        JobMafiaService g = new JobMafiaService();
        g.tSetup(List.of(Role.MAFIA, Role.MAFIA_SHADOW, Role.CITIZEN, Role.CITIZEN, Role.DOCTOR));
        g.tTarget(0, 2); // 일반마피아 → 시민2
        g.tTarget(1, 3); // 그림자마피아 → 시민3
        return g;         // 의사는 지목 안 함(보호 없음)
    }

    @Test
    void 공유_킬_모드는_밤에_한명만_죽는다() {
        JobMafiaService g = setup();
        g.tMode(false);
        g.tResolve();
        int dead = 0;
        for (int s = 2; s <= 3; s++) if (!g.tAlive(s)) dead++;
        assertThat(dead).isEqualTo(1); // 다수결 동률 → 한 명만
    }

    @Test
    void 독립_킬_모드는_능력마피아가_각자_처치한다() {
        JobMafiaService g = setup();
        g.tMode(true);
        g.tResolve();
        assertThat(g.tAlive(2)).isFalse();      // 일반마피아가 시민2 처치
        assertThat(g.tAlive(3)).isFalse();      // 그림자마피아가 시민3 처치
        assertThat(g.tConcealed(3)).isTrue();   // 그림자에게 죽은 시민3은 정체 은폐
        assertThat(g.tConcealed(2)).isFalse();  // 일반마피아 킬은 공개
    }

    @Test
    void 독립_킬도_의사_보호는_유효하다() {
        JobMafiaService g = setup();
        g.tMode(true);
        g.tTarget(4, 3);  // 의사가 시민3 보호
        g.tResolve();
        assertThat(g.tAlive(3)).isTrue();       // 그림자 공격을 의사가 막음
        assertThat(g.tAlive(2)).isFalse();      // 시민2는 그대로 사망
    }
}
