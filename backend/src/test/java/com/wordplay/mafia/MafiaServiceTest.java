package com.wordplay.mafia;

import com.wordplay.mafia.dto.MafiaStateResponse;
import com.wordplay.mafia.dto.MafiaStateResponse.PlayerView;
import com.wordplay.mafia.dto.NewMafiaRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 마피아 상태머신 핵심 로직 검증(타이머 무관 부분).
 * 밤은 모든 행동자가 선택하면 조기 진행되므로 타이머 대기 없이 테스트 가능.
 */
class MafiaServiceTest {

    private record Setup(MafiaService svc, Map<String, String> roleByClient, Map<String, Integer> seatByClient) {}

    /** 5인 방 생성 → 시작 → 각 클라이언트의 역할/좌석 수집. */
    private Setup start5() {
        MafiaService svc = new MafiaService();
        svc.newGame("host", new NewMafiaRequest("방장", null, null, null, null, null));
        for (int i = 1; i <= 4; i++) svc.join("c" + i, "p" + i);
        svc.start("host");

        Map<String, String> roleByClient = new HashMap<>();
        Map<String, Integer> seatByClient = new HashMap<>();
        for (String c : List.of("host", "c1", "c2", "c3", "c4")) {
            MafiaStateResponse s = svc.me(c);
            roleByClient.put(c, s.myRole());
            seatByClient.put(c, s.seat());
        }
        return new Setup(svc, roleByClient, seatByClient);
    }

    private String clientWithRole(Map<String, String> roles, String role) {
        return roles.entrySet().stream().filter(e -> e.getValue().equals(role))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    /** 살아있는 시민들도 위장 지목을 완료시켜 밤을 조기 종료시킨다. */
    private void citizensAct(Setup s) {
        for (var e : s.roleByClient.entrySet()) {
            if (!"CITIZEN".equals(e.getValue())) continue;
            MafiaStateResponse st = s.svc.me(e.getKey());
            if (st.status().equals("NIGHT") && st.alive() && !st.selectable().isEmpty()) {
                s.svc.nightAction(e.getKey(), st.selectable().get(0));
            }
        }
    }

    @Test
    void 방생성_참가_시작_역할배정() {
        Setup s = start5();
        long mafia = s.roleByClient.values().stream().filter("MAFIA"::equals).count();
        long police = s.roleByClient.values().stream().filter("POLICE"::equals).count();
        long doctor = s.roleByClient.values().stream().filter("DOCTOR"::equals).count();
        long citizen = s.roleByClient.values().stream().filter("CITIZEN"::equals).count();

        assertThat(mafia).isEqualTo(1);   // max(1, 5/3) = 1
        assertThat(police).isEqualTo(1);
        assertThat(doctor).isEqualTo(1);
        assertThat(citizen).isEqualTo(2);

        assertThat(s.svc.me("host").status()).isEqualTo("NIGHT");
    }

    @Test
    void 최소인원_미달_시작불가() {
        MafiaService svc = new MafiaService();
        svc.newGame("host", new NewMafiaRequest("방장", null, null, null, null, null));
        svc.join("c1", "p1");
        assertThatThrownBy(() -> svc.start("host"))
                .hasMessageContaining("최소 4명");
    }

    @Test
    void 밤_마피아지목_사망_그리고_경찰조사기록() {
        Setup s = start5();
        String mafia = clientWithRole(s.roleByClient, "MAFIA");
        String cop = clientWithRole(s.roleByClient, "POLICE");
        String doctor = clientWithRole(s.roleByClient, "DOCTOR");
        int mafiaSeat = s.seatByClient.get(mafia);

        // 마피아가 시민 하나를 지목(마피아 자신 제외한 산 사람 중 하나)
        int killSeat = s.svc.me(mafia).selectable().get(0);
        s.svc.nightAction(mafia, killSeat);
        // 경찰은 마피아를 조사
        s.svc.nightAction(cop, mafiaSeat);
        // 의사는 킬 대상이 아닌 다른 사람 보호(사망 발생시키기)
        int docSave = s.svc.me(doctor).selectable().stream().filter(x -> x != killSeat).findFirst().orElseThrow();
        s.svc.nightAction(doctor, docSave);
        citizensAct(s); // 전원 지목 완료 → 밤 종료

        MafiaStateResponse st = s.svc.me("host");
        assertThat(st.status()).isEqualTo("MORNING");
        PlayerView killed = st.players().get(killSeat - 1);
        assertThat(killed.alive()).isFalse();
        assertThat(st.nightMessage()).contains("사망");

        // 경찰 로그에 마피아 O
        assertThat(s.svc.me(cop).copLog()).anyMatch(l -> l.contains("마피아 O"));
    }

    @Test
    void 밤_의사가_킬대상_보호하면_아무도_안죽음() {
        Setup s = start5();
        String mafia = clientWithRole(s.roleByClient, "MAFIA");
        String cop = clientWithRole(s.roleByClient, "POLICE");
        String doctor = clientWithRole(s.roleByClient, "DOCTOR");

        int killSeat = s.svc.me(mafia).selectable().get(0);
        s.svc.nightAction(mafia, killSeat);
        s.svc.nightAction(cop, s.seatByClient.get(mafia));
        // 의사가 같은 대상 보호
        s.svc.nightAction(doctor, killSeat);
        citizensAct(s); // 전원 지목 완료 → 밤 종료

        MafiaStateResponse st = s.svc.me("host");
        assertThat(st.status()).isEqualTo("MORNING");
        assertThat(st.players().get(killSeat - 1).alive()).isTrue();
        assertThat(st.nightMessage()).contains("평화");
        assertThat(st.aliveCount()).isEqualTo(5);
    }

    @Test
    void 마피아_밤채팅_마피아만() {
        Setup s = start5();
        String mafia = clientWithRole(s.roleByClient, "MAFIA");
        String cop = clientWithRole(s.roleByClient, "POLICE");

        s.svc.chat(mafia, "3번 죽이자");
        assertThat(s.svc.me(mafia).mafiaChat()).anyMatch(c -> c.text().contains("3번 죽이자"));
        // 마피아가 아닌 사람은 채팅이 보이지 않음
        assertThat(s.svc.me(cop).mafiaChat()).isEmpty();
        // 마피아가 아니면 채팅 불가
        assertThatThrownBy(() -> s.svc.chat(cop, "안돼"))
                .hasMessageContaining("마피아만");
    }

    @Test
    void 진행중_참가_불가() {
        Setup s = start5();
        assertThatThrownBy(() -> s.svc.join("late", "지각"))
                .hasMessageContaining("진행 중");
    }

    @Test
    void 관리자_초기화() {
        Setup s = start5();
        assertThat(s.svc.resetGame().status()).isEqualTo("NOT_STARTED");
        assertThat(s.svc.me("host").status()).isEqualTo("NOT_STARTED");
    }
}
