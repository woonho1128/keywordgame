package com.wordplay.jobmafia;

import com.wordplay.jobmafia.dto.JobMafiaStateResponse;
import com.wordplay.jobmafia.dto.NewJobMafiaRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobMafiaServiceTest {

    private final List<String> clients = new ArrayList<>();

    /** 6인 방 생성 후 시작(정신병자·관종 자동 포함). */
    private JobMafiaService start6() {
        JobMafiaService svc = new JobMafiaService();
        clients.clear();
        // 범위 고정: 마피아1·정신병자1·관종1 → 6인이면 각 1명 + 시민1 (결정적)
        svc.newGame("host", new NewJobMafiaRequest("방장", null, null, null, 1, 1, 1, 1, 1, 1, 0, 0, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        clients.add("host");
        for (int i = 1; i <= 5; i++) { svc.join("c" + i, "p" + i); clients.add("c" + i); }
        svc.start("host");
        return svc;
    }

    @org.junit.jupiter.api.Test
    void 중립_통합모드_총_인원만_제한() throws Exception {
        for (int trial = 0; trial < 12; trial++) {
            JobMafiaService svc = new JobMafiaService();
            clients.clear();
            // 마피아1·정신병자0, 중립(관종·도적꾼) 통합 정확히 1명
            svc.newGame("host", new NewJobMafiaRequest("방장", null, null, null, 1, 1, 0, 0, 0, 0, 0, 0, true, 1, 1, null, null, null, null, null, null, null, null, null, null, null, null, null));
            clients.add("host");
            for (int i = 1; i <= 5; i++) { svc.join("c" + i, "p" + i); clients.add("c" + i); }
            svc.start("host");
            List<String> roles = trueRoles(svc);
            long neutral = roles.stream().filter(r -> "ATTENTION".equals(r) || "THIEF".equals(r)).count();
            org.assertj.core.api.Assertions.assertThat(neutral).isEqualTo(1); // 관종+도적꾼 합이 정확히 1
        }
    }

    @org.junit.jupiter.api.Test
    void 특수마피아_마피아총원_안에서_배정() throws Exception {
        for (int trial = 0; trial < 10; trial++) {
            JobMafiaService svc = new JobMafiaService();
            clients.clear();
            // 마피아 정확히 2명, 그중 경찰마피아1 + 그림자마피아1 → 일반 마피아 0
            svc.newGame("host", new NewJobMafiaRequest("방장", null, null, null, 2, 2, 0, 0, 0, 0, 0, 0, null, null, null, 1, 1, 1, 1, null, null, null, null, null, null, null, null, null));
            clients.add("host");
            for (int i = 1; i <= 6; i++) { svc.join("c" + i, "p" + i); clients.add("c" + i); }
            svc.start("host");
            List<String> roles = trueRoles(svc);
            org.assertj.core.api.Assertions.assertThat(roles.stream().filter("MAFIA_COP"::equals).count()).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(roles.stream().filter("MAFIA_SHADOW"::equals).count()).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(roles.stream().filter("MAFIA"::equals).count()).isEqualTo(0);
        }
    }

    /** 리플렉션으로 좌석별 실제 직업을 읽는다(정신병자는 응답에서 가짜라서). */
    private List<String> trueRoles(JobMafiaService svc) throws Exception {
        Field pf = JobMafiaService.class.getDeclaredField("players");
        pf.setAccessible(true);
        List<?> players = (List<?>) pf.get(svc);
        List<String> roles = new ArrayList<>();
        for (Object p : players) {
            Field rf = p.getClass().getDeclaredField("role");
            rf.setAccessible(true);
            Object r = rf.get(p);
            roles.add(r == null ? null : r.toString());
        }
        return roles;
    }

    private String clientAtSeat(JobMafiaService svc, int seatIdx0) {
        for (String c : clients) if (svc.me(c).seat() == seatIdx0 + 1) return c;
        throw new IllegalStateException("no client at seat " + seatIdx0);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setRole(JobMafiaService svc, int seat, String roleName) throws Exception {
        Field pf = JobMafiaService.class.getDeclaredField("players");
        pf.setAccessible(true);
        Object player = ((List<?>) pf.get(svc)).get(seat);
        Field rf = player.getClass().getDeclaredField("role");
        rf.setAccessible(true);
        Class<?> roleEnum = Class.forName("com.wordplay.jobmafia.JobMafiaService$Role");
        rf.set(player, Enum.valueOf((Class) roleEnum, roleName));
    }

    private void forcePhaseEnd(JobMafiaService svc) throws Exception {
        Field ef = JobMafiaService.class.getDeclaredField("phaseEndsAt");
        ef.setAccessible(true);
        ef.setLong(svc, 1L);
    }

    @Test
    void 도적꾼_직업_강탈() throws Exception {
        JobMafiaService svc = start6();
        setRole(svc, 0, "THIEF");
        setRole(svc, 1, "DOCTOR");
        setRole(svc, 2, "MAFIA"); // 마피아 생존 보장(게임 계속)
        String thief = clientAtSeat(svc, 0);
        svc.nightAction(thief, 2); // 1-based 좌석 2 = 의사(seat1) 지목
        forcePhaseEnd(svc);
        svc.me(thief);             // tick → resolveNight(강탈)
        List<String> roles = trueRoles(svc);
        assertThat(roles.get(0)).isEqualTo("DOCTOR");  // 도적꾼이 의사가 됨
        assertThat(roles.get(1)).isEqualTo("CITIZEN"); // 피해자는 무직
    }

    @Test
    void 직업구성_6인() throws Exception {
        JobMafiaService svc = start6();
        List<String> roles = trueRoles(svc);
        assertThat(roles).filteredOn("MAFIA"::equals).hasSize(1);
        assertThat(roles).filteredOn("POLICE"::equals).hasSize(1);
        assertThat(roles).filteredOn("DOCTOR"::equals).hasSize(1);
        assertThat(roles).filteredOn("PSYCHO"::equals).hasSize(1);
        assertThat(roles).filteredOn("ATTENTION"::equals).hasSize(1);
        assertThat(roles).filteredOn("CITIZEN"::equals).hasSize(1);
    }

    @Test
    void 정신병자는_가짜직업_시민팀으로_보임() throws Exception {
        JobMafiaService svc = start6();
        List<String> roles = trueRoles(svc);
        int psychoSeat = roles.indexOf("PSYCHO");
        String psychoClient = clientAtSeat(svc, psychoSeat);

        JobMafiaStateResponse st = svc.me(psychoClient);
        assertThat(st.myRole()).isIn("POLICE", "DOCTOR", "OBSERVER", "BLOCKER"); // 가짜 직업
        assertThat(st.myRole()).isNotEqualTo("PSYCHO");
        assertThat(st.myTeam()).isEqualTo("CITIZEN");     // 시민팀과 함께 승리
    }

    @Test
    void 밤_마피아_지목_사망() throws Exception {
        JobMafiaService svc = start6();
        List<String> roles = trueRoles(svc);

        String mafia = clientAtSeat(svc, roles.indexOf("MAFIA"));
        int killSeat = svc.me(mafia).selectable().get(0); // 1-based
        svc.nightAction(mafia, killSeat);

        // 의사는 killSeat이 아닌 다른 사람 보호
        String doctor = clientAtSeat(svc, roles.indexOf("DOCTOR"));
        int protect = svc.me(doctor).selectable().stream().filter(s -> s != killSeat).findFirst().orElseThrow();
        svc.nightAction(doctor, protect);

        // 나머지 생존자도 지목 완료 → 밤 종료 (마피아·의사는 이미 정한 대상 유지)
        for (String c : clients) {
            if (c.equals(mafia) || c.equals(doctor)) continue;
            JobMafiaStateResponse st = svc.me(c);
            if (st.status().equals("NIGHT") && st.alive() && !st.selectable().isEmpty()) {
                svc.nightAction(c, st.selectable().get(0));
            }
        }

        JobMafiaStateResponse st = svc.me("host");
        assertThat(st.status()).isEqualTo("MORNING");
        assertThat(st.players().get(killSeat - 1).alive()).isFalse();
    }

    @Test
    void 최소인원_미달_시작불가() {
        JobMafiaService svc = new JobMafiaService();
        svc.newGame("host", new NewJobMafiaRequest("방장", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        for (int i = 1; i <= 3; i++) svc.join("c" + i, "p" + i);
        assertThatThrownBy(() -> svc.start("host")).hasMessageContaining("최소 5명");
    }

    @Test
    void 관리자_초기화() {
        JobMafiaService svc = start6();
        assertThat(svc.resetGame().status()).isEqualTo("NOT_STARTED");
        assertThat(svc.me("host").status()).isEqualTo("NOT_STARTED");
    }
}
