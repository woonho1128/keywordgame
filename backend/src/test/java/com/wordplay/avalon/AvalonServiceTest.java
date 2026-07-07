package com.wordplay.avalon;

import com.wordplay.avalon.dto.AvalonStateResponse;
import com.wordplay.avalon.dto.NewAvalonRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvalonServiceTest {

    private final List<String> clients = new ArrayList<>();

    /** 7인 방 생성 → 시작 → REVEAL 전원 확인 → TEAM_BUILD. (퍼시발+모르가나 기본 포함) */
    private AvalonService start7() {
        AvalonService svc = new AvalonService();
        clients.clear();
        svc.newGame("host", new NewAvalonRequest("방장", null, null, null));
        clients.add("host");
        for (int i = 1; i <= 6; i++) { svc.join("c" + i, "p" + i); clients.add("c" + i); }
        svc.start("host");
        for (String c : clients) svc.ready(c);
        return svc;
    }

    private List<String> trueRoles(AvalonService svc) throws Exception {
        Field pf = AvalonService.class.getDeclaredField("players");
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

    private String clientAtSeat(AvalonService svc, int seat1) {
        for (String c : clients) if (svc.me(c).seat() == seat1) return c;
        throw new IllegalStateException("no client at seat " + seat1);
    }

    /** 현재 TEAM_BUILD에서 원정 하나를 전원 승인·전원 성공으로 통과시킨다. */
    private void passQuestSuccess(AvalonService svc) {
        AvalonStateResponse st = svc.me("host");
        assertThat(st.status()).isEqualTo("TEAM_BUILD");
        String leader = clientAtSeat(svc, st.leaderSeat());
        int need = st.teamSizeNeeded();
        List<Integer> team = new ArrayList<>();
        for (int s = 1; s <= need; s++) team.add(s);
        svc.propose(leader, team);
        for (String c : clients) svc.vote(c, true);      // 전원 승인
        for (int s : team) svc.quest(clientAtSeat(svc, s), true); // 전원 성공
    }

    @Test
    void 구성_7인() throws Exception {
        AvalonService svc = start7();
        List<String> roles = trueRoles(svc);
        assertThat(roles).hasSize(7);
        assertThat(roles).filteredOn(r -> List.of("ASSASSIN", "MORGANA", "MORDRED", "OBERON", "MINION").contains(r)).hasSize(3);
        assertThat(roles).filteredOn("MERLIN"::equals).hasSize(1);
        assertThat(roles).filteredOn("ASSASSIN"::equals).hasSize(1);
        assertThat(roles).filteredOn("PERCIVAL"::equals).hasSize(1);
        assertThat(roles).filteredOn("MORGANA"::equals).hasSize(1);
        assertThat(svc.me("host").status()).isEqualTo("TEAM_BUILD");
    }

    @Test
    void 멀린은_악을_안다() throws Exception {
        AvalonService svc = start7();
        List<String> roles = trueRoles(svc);
        String merlin = clientAtSeat(svc, roles.indexOf("MERLIN") + 1);
        String assassinNick = svc.me(clientAtSeat(svc, roles.indexOf("ASSASSIN") + 1)).nick();
        String know = String.join(" ", svc.me(merlin).knowledge());
        assertThat(know).contains("악의 하수인").contains(assassinNick);
    }

    @Test
    void 원정_3성공후_암살실패시_선_승리() throws Exception {
        AvalonService svc = start7();
        passQuestSuccess(svc);
        passQuestSuccess(svc);
        passQuestSuccess(svc);
        // 원정 3성공 → 암살 단계
        assertThat(svc.me("host").status()).isEqualTo("ASSASSIN");

        List<String> roles = trueRoles(svc);
        String assassin = clientAtSeat(svc, roles.indexOf("ASSASSIN") + 1);
        int merlinSeat = roles.indexOf("MERLIN") + 1;
        int notMerlin = merlinSeat == 1 ? 2 : 1; // 멀린이 아닌 좌석 지목
        AvalonStateResponse end = svc.assassinate(assassin, notMerlin);
        assertThat(end.status()).isEqualTo("ENDED");
        assertThat(end.winner()).isEqualTo("GOOD");
    }

    @Test
    void 암살자가_멀린을_맞히면_악_승리() throws Exception {
        AvalonService svc = start7();
        passQuestSuccess(svc);
        passQuestSuccess(svc);
        passQuestSuccess(svc);
        assertThat(svc.me("host").status()).isEqualTo("ASSASSIN");

        List<String> roles = trueRoles(svc);
        String assassin = clientAtSeat(svc, roles.indexOf("ASSASSIN") + 1);
        AvalonStateResponse end = svc.assassinate(assassin, roles.indexOf("MERLIN") + 1);
        assertThat(end.status()).isEqualTo("ENDED");
        assertThat(end.winner()).isEqualTo("EVIL");
    }

    @Test
    void 원정대_5연속_거부시_악_승리() {
        AvalonService svc = start7();
        for (int round = 0; round < 5; round++) {
            AvalonStateResponse st = svc.me("host");
            if (!st.status().equals("TEAM_BUILD")) break;
            String leader = clientAtSeat(svc, st.leaderSeat());
            List<Integer> team = new ArrayList<>();
            for (int s = 1; s <= st.teamSizeNeeded(); s++) team.add(s);
            svc.propose(leader, team);
            for (String c : clients) svc.vote(c, false); // 전원 거부
        }
        AvalonStateResponse st = svc.me("host");
        assertThat(st.status()).isEqualTo("ENDED");
        assertThat(st.winner()).isEqualTo("EVIL");
    }

    @Test
    void 최소인원_미달_시작불가() {
        AvalonService svc = new AvalonService();
        svc.newGame("host", new NewAvalonRequest("방장", null, null, null));
        for (int i = 1; i <= 3; i++) svc.join("c" + i, "p" + i);
        assertThatThrownBy(() -> svc.start("host")).hasMessageContaining("최소 5명");
    }

    @Test
    void 관리자_초기화() {
        AvalonService svc = start7();
        assertThat(svc.resetGame().status()).isEqualTo("NOT_STARTED");
        assertThat(svc.me("host").status()).isEqualTo("NOT_STARTED");
    }
}
