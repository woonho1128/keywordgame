package com.wordplay.mafia;

import com.wordplay.mafia.dto.NewMafiaRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 경찰 커밍아웃.
 *
 * <p>봇 경찰이 "불리하다"며 절대 정체를 안 밝힌다는 지적. 원인은 판단 실패가 아니라
 * 프롬프트에서 커밍아웃을 세 군데나 금지해둔 것이었다. 이제 조건이 맞으면 밝히게 하고,
 * 밝힌 뒤에는 밤에 실제로 결과가 따르게 한다(마피아는 노리고, 의사는 지킨다).
 */
class MafiaPoliceRevealTest {

    private static MafiaService started(int humans) {
        MafiaService svc = new MafiaService();
        svc.newGame("host", new NewMafiaRequest("방장", null, null, null, null, null, null, null));
        for (int i = 1; i < humans; i++) svc.join("p" + i, "사람" + i);
        svc.start("host");
        ReflectionTestUtils.setField(svc, "phase", MafiaService.Phase.DISCUSS);
        ReflectionTestUtils.setField(svc, "phaseEndsAt", System.currentTimeMillis() + 600_000L);
        return svc;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> claimed(MafiaService svc) {
        return (List<Integer>) ReflectionTestUtils.invokeMethod(svc, "claimedPoliceSeats");
    }

    private static boolean asked(MafiaService svc) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(svc, "askedForPolice"));
    }

    @Test
    void 커밍아웃_발언을_알아본다() {
        MafiaService svc = started(4);
        svc.sendChat("p1", "내가 경찰인데 사람2가 마피아야");
        assertThat(claimed(svc)).containsExactly(1);
    }

    @Test
    void 남을_묻는_말은_커밍아웃이_아니다() {
        MafiaService svc = started(4);
        svc.sendChat("p1", "너 경찰이야? 아까부터 아는 척하네");
        svc.sendChat("p2", "경찰 누구야 대체");
        assertThat(claimed(svc)).isEmpty();
    }

    @Test
    void 경찰이_아니라고_부정하는_말은_커밍아웃이_아니다() {
        assertThat(MafiaService.isPoliceClaim("난 경찰 아니야 그냥 시민임")).isFalse();
        assertThat(MafiaService.isPoliceClaim("내가 경찰이 아니라는 건 아까 말했잖아")).isFalse();
        assertThat(MafiaService.isPoliceClaim("사람2가 경찰이다 라고 하던데")).isFalse();
        assertThat(MafiaService.isPoliceClaim("난 경찰이야")).isTrue();
        assertThat(MafiaService.isPoliceClaim("경찰입니다 사람3 조사했어요")).isTrue();
    }

    @Test
    void 경찰_요청을_알아본다() {
        MafiaService svc = started(4);
        assertThat(asked(svc)).isFalse();
        svc.sendChat("host", "경찰 있으면 나와주세요");
        assertThat(asked(svc)).isTrue();
    }

    @Test
    void 마피아_봇은_커밍아웃한_사람을_밤에_노린다() {
        MafiaService svc = started(6);
        List<?> players = (List<?>) ReflectionTestUtils.getField(svc, "players");
        // 0=마피아, 3=커밍아웃한 경찰로 고정
        setRole(players.get(0), "MAFIA");
        for (int i = 1; i < 6; i++) setRole(players.get(i), "CITIZEN");
        setRole(players.get(3), "POLICE");
        svc.sendChat("p3", "내가 경찰이다 사람1이 마피아야");

        Object target = ReflectionTestUtils.invokeMethod(svc, "chooseNightTarget", players.get(0));
        assertThat(target).isEqualTo(3);
    }

    @Test
    void 의사_봇은_커밍아웃한_경찰을_지킨다() {
        MafiaService svc = started(6);
        List<?> players = (List<?>) ReflectionTestUtils.getField(svc, "players");
        setRole(players.get(0), "DOCTOR");
        for (int i = 1; i < 6; i++) setRole(players.get(i), "CITIZEN");
        setRole(players.get(2), "POLICE");
        svc.sendChat("p2", "제가 경찰입니다");

        Object target = ReflectionTestUtils.invokeMethod(svc, "chooseNightTarget", players.get(0));
        assertThat(target).isEqualTo(2);
    }

    @Test
    void 마피아가_위장_커밍아웃해도_동료를_죽이지_않는다() {
        MafiaService svc = started(6);
        List<?> players = (List<?>) ReflectionTestUtils.getField(svc, "players");
        setRole(players.get(0), "MAFIA");
        setRole(players.get(1), "MAFIA");
        for (int i = 2; i < 6; i++) setRole(players.get(i), "CITIZEN");
        svc.sendChat("p1", "내가 경찰이야");   // 마피아 동료의 역커밍아웃

        Object target = ReflectionTestUtils.invokeMethod(svc, "chooseNightTarget", players.get(0));
        assertThat(target).isNotEqualTo(1);
    }

    @Test
    void 마피아를_찾으면_밝히라고_지시한다() {
        MafiaService svc = started(5);
        List<?> players = (List<?>) ReflectionTestUtils.getField(svc, "players");
        setRole(players.get(1), "POLICE");

        // 조사에서 2번이 마피아로 나온 상태
        @SuppressWarnings("unchecked")
        Map<Integer, Boolean> findings =
                (Map<Integer, Boolean>) ReflectionTestUtils.getField(svc, "copFindings");
        findings.putAll(new HashMap<>(Map.of(2, true)));
        // 이미 한 번 발언한 상태로 둔다
        @SuppressWarnings("unchecked")
        Map<Integer, Integer> counts =
                (Map<Integer, Integer>) ReflectionTestUtils.getField(svc, "botChatCount");
        counts.put(1, 1);

        String intent = (String) ReflectionTestUtils.invokeMethod(svc, "chatIntent", players.get(1));
        assertThat(intent).contains("내가 경찰이다");
    }

    @Test
    void 조사결과가_없으면_밝히라고_하지_않는다() {
        MafiaService svc = started(5);
        List<?> players = (List<?>) ReflectionTestUtils.getField(svc, "players");
        setRole(players.get(1), "POLICE");
        @SuppressWarnings("unchecked")
        Map<Integer, Integer> counts =
                (Map<Integer, Integer>) ReflectionTestUtils.getField(svc, "botChatCount");
        counts.put(1, 1);

        String intent = (String) ReflectionTestUtils.invokeMethod(svc, "chatIntent", players.get(1));
        assertThat(intent).doesNotContain("내가 경찰이다");
    }

    private static void setRole(Object player, String role) {
        ReflectionTestUtils.setField(player, "role", Enum.valueOf(MafiaService.Role.class, role));
    }
}
