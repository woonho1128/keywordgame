package com.wordplay.mafia;

import com.wordplay.mafia.dto.NewMafiaRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 봇이 라운드를 넘겨서도 사실을 기억하는지.
 *
 * <p>봇의 기억은 따로 저장하지 않고 서버가 매번 사실로 다시 만든다. 그래서
 * "무엇을 넣어주는가"가 곧 봇의 기억이다. 특히 개인별 투표 기록은 마피아를
 * 잡는 1순위 근거인데 예전엔 프롬프트에 아예 없었다(집계만 있었다).
 */
class MafiaBotMemoryTest {

    private static MafiaService lobbyOf(int humans) {
        MafiaService svc = new MafiaService();
        svc.newGame("host", new NewMafiaRequest("방장", null, null, null, null, null, null, null));
        for (int i = 1; i < humans; i++) svc.join("p" + i, "사람" + i);
        return svc;
    }

    /** 채팅을 넣을 수 있도록 토론 단계로 고정한다(타이머로 넘어가지 않게 넉넉히). */
    private static void holdInDiscuss(MafiaService svc) {
        ReflectionTestUtils.setField(svc, "phase",
                ReflectionTestUtils.invokeMethod(MafiaService.Phase.class, "valueOf", "DISCUSS"));
        ReflectionTestUtils.setField(svc, "phaseEndsAt", System.currentTimeMillis() + 600_000L);
    }

    /** 프롬프트 조립은 private이라 리플렉션으로 부른다. */
    private static String context(MafiaService svc, int seat) {
        List<?> players = (List<?>) ReflectionTestUtils.getField(svc, "players");
        return (String) ReflectionTestUtils.invokeMethod(svc, "chatContext", players.get(seat));
    }

    @SuppressWarnings("unchecked")
    private static void putVotes(MafiaService svc, long round, Map<Integer, Integer> v) {
        Map<Long, Map<Integer, Integer>> h =
                (Map<Long, Map<Integer, Integer>>) ReflectionTestUtils.getField(svc, "voteHistory");
        h.put(round, new LinkedHashMap<>(v));
    }

    @Test
    void 개인별_투표_기록이_프롬프트에_들어간다() {
        MafiaService svc = lobbyOf(4);
        svc.start("host");
        // 1일차: 사람1·사람2가 나란히 사람3을 찍었다(같이 몬 흔적).
        putVotes(svc, 1L, Map.of(1, 3, 2, 3, 3, -1));

        String ctx = context(svc, 0);
        assertThat(ctx).contains("[지난 투표 기록 — 누가 누구를 찍었나]");
        assertThat(ctx).contains("사람3 ←");
        assertThat(ctx).contains("사람1").contains("사람2");
        assertThat(ctx).contains("기권");
    }

    @Test
    void 투표_기록은_최근_두_라운드만_넣는다() {
        MafiaService svc = lobbyOf(4);
        svc.start("host");
        putVotes(svc, 1L, Map.of(1, 2));
        putVotes(svc, 2L, Map.of(1, 3));
        putVotes(svc, 3L, Map.of(2, 1));

        String ctx = context(svc, 0);
        assertThat(ctx).doesNotContain("1일차:");     // 오래된 건 잘라낸다(프롬프트 비대화 방지)
        assertThat(ctx).contains("2일차:").contains("3일차:");
    }

    @Test
    void 지난_라운드에_자기가_한_말을_다시_받는다() {
        MafiaService svc = lobbyOf(4);
        svc.start("host");
        holdInDiscuss(svc);
        svc.sendChat("host", "나는 사람2가 수상하다고 본다");   // 1일차 발언
        ReflectionTestUtils.setField(svc, "round", 2L);        // 다음 날로

        String ctx = context(svc, 0);
        assertThat(ctx).contains("[전에 네가 한 말");
        assertThat(ctx).contains("나는 사람2가 수상하다고 본다");

        // 남에게는 개인 블록이 안 간다(정보 격리).
        assertThat(context(svc, 1)).doesNotContain("[전에 네가 한 말");
    }

    @Test
    void 오늘_나를_언급한_사람이_표시된다() {
        MafiaService svc = lobbyOf(4);
        svc.start("host");
        holdInDiscuss(svc);
        svc.sendChat("p1", "방장 너 아까부터 말 돌리잖아");

        assertThat(context(svc, 0)).contains("[오늘 너를 언급한 사람] 사람1");
        assertThat(context(svc, 2)).doesNotContain("[오늘 너를 언급한 사람]");
    }

    @Test
    void 정체공개를_끄면_처형자_정체가_이력에_남지_않는다() {
        MafiaService svc = new MafiaService();
        svc.newGame("host", new NewMafiaRequest("방장", null, null, null, null, false, null, null));
        for (int i = 1; i < 4; i++) svc.join("p" + i, "사람" + i);
        svc.start("host");

        ReflectionTestUtils.setField(svc, "accusedSeat", 1);
        @SuppressWarnings("unchecked")
        Map<Integer, Boolean> fv = (Map<Integer, Boolean>) ReflectionTestUtils.getField(svc, "finalVotes");
        fv.put(0, true); fv.put(2, true); fv.put(3, true);
        ReflectionTestUtils.invokeMethod(svc, "resolveFinalVote");

        @SuppressWarnings("unchecked")
        List<String> history = (List<String>) ReflectionTestUtils.getField(svc, "history");
        assertThat(history).anyMatch(h -> h.contains("사람1님 처형"));
        assertThat(history).as("정체 공개를 껐으면 이력에도 안 나와야 한다")
                .noneMatch(h -> h.contains("정체:"));
    }
}
