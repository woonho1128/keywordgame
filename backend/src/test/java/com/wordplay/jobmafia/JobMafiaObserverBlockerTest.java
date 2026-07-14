package com.wordplay.jobmafia;

import com.wordplay.jobmafia.JobMafiaService.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 관찰자·봉쇄자(시민/마피아) 동작 검증. */
class JobMafiaObserverBlockerTest {

    @Test
    void 봉쇄자는_마피아_킬을_막는다() {
        JobMafiaService g = new JobMafiaService();
        g.tSetup(List.of(Role.MAFIA, Role.BLOCKER, Role.CITIZEN)); // 0마피아 1봉쇄자 2시민
        g.tTarget(0, 2); // 마피아 → 시민2 살해 시도
        g.tTarget(1, 0); // 봉쇄자 → 마피아 봉쇄
        g.tResolve();
        assertThat(g.tAlive(2)).isTrue(); // 마피아가 봉쇄당해 킬 실패
    }

    @Test
    void 관찰자는_대상이_밤에_누구를_지목했는지_본다() {
        JobMafiaService g = new JobMafiaService();
        g.tSetup(List.of(Role.OBSERVER, Role.MAFIA, Role.CITIZEN)); // 0관찰자 1마피아 2시민
        g.tTarget(1, 2); // 마피아 → 시민2 지목
        g.tTarget(0, 1); // 관찰자 → 마피아 관찰
        g.tResolve();
        List<String> log = g.tMyLog(0);
        assertThat(log).anyMatch(s -> s.contains("관찰") && s.contains("P2")); // 대상(마피아)이 P2를 지목함을 봄
    }

    @Test
    void 봉쇄당한_관찰자는_정보를_못_얻는다() {
        JobMafiaService g = new JobMafiaService();
        g.tSetup(List.of(Role.OBSERVER, Role.MAFIA_BLOCKER, Role.CITIZEN)); // 0관찰자 1봉쇄자마피아 2시민
        g.tAbilityMode(1, true);  // 봉쇄자마피아: 능력(봉쇄) 모드
        g.tTarget(1, 0); // 봉쇄자마피아 → 관찰자 봉쇄
        g.tTarget(0, 2); // 관찰자 → 시민2 관찰 시도
        g.tResolve();
        assertThat(g.tMyLog(0)).anyMatch(s -> s.contains("방해")); // 관찰 실패
    }

    @Test
    void 봉쇄된_대상은_관찰자에게_행동없음으로_보인다() {
        // 순서: 봉쇄자 먼저 → 관찰자 마지막. 마피아가 봉쇄당하면 관찰자엔 '행동 못함'
        JobMafiaService g = new JobMafiaService();
        g.tSetup(List.of(Role.OBSERVER, Role.BLOCKER, Role.MAFIA, Role.CITIZEN)); // 0관찰 1봉쇄 2마피아 3시민
        g.tTarget(1, 2); // 봉쇄자 → 마피아 봉쇄
        g.tTarget(2, 3); // 마피아 → 시민3 지목(하지만 봉쇄됨)
        g.tTarget(0, 2); // 관찰자 → 마피아 관찰
        g.tResolve();
        assertThat(g.tAlive(3)).isTrue();  // 마피아 봉쇄당해 킬 실패
        assertThat(g.tMyLog(0)).anyMatch(s -> s.contains("관찰") && s.contains("하지 못함")); // 대상이 행동 못함
        assertThat(g.tMyLog(0)).noneMatch(s -> s.contains("관찰") && s.contains("P3")); // P3 지목 안 보임
    }

    @Test
    void 관찰자마피아_능력모드는_킬하지_않는다() {
        JobMafiaService g = new JobMafiaService();
        g.tSetup(List.of(Role.MAFIA_OBSERVER, Role.CITIZEN, Role.CITIZEN)); // 0관찰자마피아 1,2시민
        g.tAbilityMode(0, true); // 능력(관찰) 모드
        g.tTarget(0, 1); // 시민1 관찰
        g.tResolve();
        assertThat(g.tAlive(1)).isTrue();  // 능력모드라 안 죽임
        assertThat(g.tMyLog(0)).anyMatch(s -> s.contains("관찰"));
    }
}
