package com.wordplay.mafia;

import com.wordplay.mafia.dto.NewMafiaRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 첫 발언이 봇마다 달라야 한다.
 *
 * <p>실제 판에서 봇 넷이 첫 발언으로 전부 같은 말을 했다 —
 * "첫날 밤 사망이면 아직 단서는 없네, 일단 첫 발언부터 보자" / "어젯밤 봇2만 죽었고
 * 투표 기록은 아직 없네, 일단 말부터 들어보자" / ... 전원에게 같은 지시("사실을 정리해라")를
 * 줬는데 1일차에는 정리할 사실이 없으니 넷 다 같은 결론으로 수렴한 것이다.
 */
class MafiaOpeningVarietyTest {

    private static MafiaService withBots(int bots) {
        MafiaService svc = new MafiaService(new com.wordplay.mafia.ai.MafiaBotRuntime(null) {
            @Override public boolean available() { return true; }
            @Override public void submit(Runnable task) { }
            @Override public String chat(String user) { return null; }
            @Override public String vote(String user) { return "0"; }
        });
        svc.newGame("host", new NewMafiaRequest("방장", null, null, null, null, null, null, null));
        svc.addBots(bots);
        svc.start("host");
        return svc;
    }

    private static String intent(MafiaService svc, Object player) {
        return (String) ReflectionTestUtils.invokeMethod(svc, "chatIntent", player);
    }

    @Test
    void 봇마다_첫_발언_지시가_다르다() {
        MafiaService svc = withBots(4);
        List<?> players = (List<?>) ReflectionTestUtils.getField(svc, "players");
        // 역할 차이를 배제하고 시민으로 통일 — 순수하게 봇 순서로만 갈리는지 본다.
        for (int i = 0; i < 5; i++)
            ReflectionTestUtils.setField(players.get(i), "role", MafiaService.Role.CITIZEN);

        Set<String> seen = new LinkedHashSet<>();
        for (int seat = 1; seat <= 4; seat++) seen.add(intent(svc, players.get(seat)));

        assertThat(seen).as("봇 4명이면 첫 지시가 4가지 다 달라야 한다").hasSize(4);
    }

    @Test
    void 첫_지시가_서로_다른_종류의_행동을_시킨다() {
        MafiaService svc = withBots(4);
        List<?> players = (List<?>) ReflectionTestUtils.getField(svc, "players");
        for (int i = 0; i < 5; i++)
            ReflectionTestUtils.setField(players.get(i), "role", MafiaService.Role.CITIZEN);

        String all = "";
        for (int seat = 1; seat <= 4; seat++) all += intent(svc, players.get(seat)) + "\n";

        // 밤 결과 해석 / 질문 / 진행 제안 / 자기 이야기 — 네 갈래가 다 나와야 한다.
        assertThat(all).contains("네 해석");
        assertThat(all).contains("질문을 던져라");
        assertThat(all).contains("오늘 어떻게 진행할지 제안");
        assertThat(all).contains("네 이야기부터 꺼내라");
    }

    @Test
    void 표현만_다른_같은_말을_반복으로_잡는다() {
        // 실제로 나왔던 두 발언(정규화 후 비교)
        String a = "첫날밤사망이면아직단서는없네일단다들첫발언부터보자";
        String b = "첫날밤사망만으로는판단어렵고일단다들첫발언부터차분히보자";
        assertThat(MafiaService.bigramOverlap(a, b)).isGreaterThanOrEqualTo(0.5);

        // 내용이 다른 말은 반복이 아니다.
        String c = "봇3은어제왜나한테투표했는지설명해봐";
        assertThat(MafiaService.bigramOverlap(a, c)).isLessThan(0.5);
    }

    @Test
    void 죽은_사람의_공개된_정체를_봇도_안다() {
        MafiaService svc = withBots(4);
        List<?> players = (List<?>) ReflectionTestUtils.getField(svc, "players");
        ReflectionTestUtils.setField(players.get(2), "role", MafiaService.Role.DOCTOR);
        ReflectionTestUtils.setField(players.get(2), "alive", false);

        String ctx = (String) ReflectionTestUtils.invokeMethod(svc, "chatContext", players.get(1));
        assertThat(ctx).contains("[공개된 정체");
        assertThat(ctx).contains("의사");
    }

    @Test
    void 정체공개를_끄면_봇에게도_알려주지_않는다() {
        MafiaService svc = new MafiaService();
        svc.newGame("host", new NewMafiaRequest("방장", null, null, null, null, false, null, null));
        for (int i = 1; i < 4; i++) svc.join("p" + i, "사람" + i);
        svc.start("host");

        List<?> players = (List<?>) ReflectionTestUtils.getField(svc, "players");
        ReflectionTestUtils.setField(players.get(2), "role", MafiaService.Role.DOCTOR);
        ReflectionTestUtils.setField(players.get(2), "alive", false);

        String ctx = (String) ReflectionTestUtils.invokeMethod(svc, "chatContext", players.get(1));
        assertThat(ctx).doesNotContain("[공개된 정체");
    }

    /** 프롬프트 조립이 깨지지 않는지(맵 접근 등) 확인. */
    @Test
    void 공개정체와_투표기록이_함께_들어가도_문제없다() {
        MafiaService svc = withBots(4);
        List<?> players = (List<?>) ReflectionTestUtils.getField(svc, "players");
        ReflectionTestUtils.setField(players.get(3), "alive", false);
        @SuppressWarnings("unchecked")
        Map<Long, Map<Integer, Integer>> vh =
                (Map<Long, Map<Integer, Integer>>) ReflectionTestUtils.getField(svc, "voteHistory");
        vh.put(1L, Map.of(0, 3, 1, 3, 2, -1));

        String ctx = (String) ReflectionTestUtils.invokeMethod(svc, "chatContext", players.get(1));
        assertThat(ctx).contains("[공개된 정체").contains("[지난 투표 기록");
    }
}
