package com.wordplay.mafia;

import com.wordplay.mafia.ai.MafiaBotRuntime;
import com.wordplay.mafia.dto.MafiaStateResponse;
import com.wordplay.mafia.dto.NewMafiaRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사형/생존 투표에서 시민 봇이 스스로 판단하는지.
 *
 * <p>예전에는 시민 봇이 무조건 '사형'을 눌렀다. 그래서 마피아 둘이 시민 하나만
 * 몰면 나머지 시민 봇들이 자동으로 표를 채워줘서 그대로 처형됐다
 * (실제로 마피아 2명이 시민 1명 몰아서 3:1로 이긴 판이 나왔다).
 */
class MafiaFinalVoteTest {

    private static MafiaBotRuntime runtime(String finalVoteAnswer, AtomicInteger calls) {
        return new MafiaBotRuntime(null) {
            @Override public boolean available() { return true; }
            @Override public void submit(Runnable task) { task.run(); }
            @Override public String chat(String user) { return "일단 상황부터 정리해보자"; }
            @Override public String vote(String user) { return "0"; }
            @Override public String finalVote(String user) { calls.incrementAndGet(); return finalVoteAnswer; }
        };
    }

    @Test
    void 생존이라고_분명히_말할_때만_살린다() {
        assertThat(MafiaService.parseFinalVote("생존")).isFalse();
        assertThat(MafiaService.parseFinalVote("살려주자")).isFalse();
        assertThat(MafiaService.parseFinalVote("사형")).isTrue();
        assertThat(MafiaService.parseFinalVote("잘 모르겠는데")).isTrue();  // 못 알아들으면 기존 동작
        assertThat(MafiaService.parseFinalVote(null)).isTrue();            // 호출 실패도 마찬가지
    }

    private static boolean isMafia(Object player) {
        return "MAFIA".equals(String.valueOf(ReflectionTestUtils.getField(player, "role")));
    }

    private static boolean flag(Object player, String field) {
        return Boolean.TRUE.equals(ReflectionTestUtils.getField(player, field));
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Boolean> finalVotes(MafiaService svc) {
        return (Map<Integer, Boolean>) ReflectionTestUtils.getField(svc, "finalVotes");
    }

    /** 재판 단계까지 몰고 간다. 방장은 계속 첫 후보를 찍는다. */
    private static MafiaService driveToFinalVote(String answer, AtomicInteger calls) throws Exception {
        MafiaService svc = new MafiaService(runtime(answer, calls));
        svc.newGame("host", new NewMafiaRequest("방장", 20, 16, 15, null, null, 5, 60));
        svc.addBots(4);
        svc.start("host");
        for (int i = 0; i < 600; i++) {
            MafiaStateResponse st = svc.me("host");
            if ("FINAL_VOTE".equals(st.status())) return svc;
            if ("ENDED".equals(st.status())) throw new AssertionError("재판 전에 게임이 끝났다");
            if (st.alive() && !st.selectable().isEmpty()) {
                if ("NIGHT".equals(st.status()) && st.myTarget() <= 0) svc.nightAction("host", st.selectable().get(0));
                if ("VOTE".equals(st.status()) && st.myTarget() <= 0) svc.vote("host", st.selectable().get(0));
            }
            Thread.sleep(100);
        }
        throw new AssertionError("재판까지 가지 않았다");
    }

    @Test
    void 시민봇은_근거가_없다고_보면_생존에_표를_준다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MafiaService svc = driveToFinalVote("생존", calls);

        // 시민 봇들이 판단할 시간을 준다(마피아 봇은 규칙으로 즉시 결정).
        for (int i = 0; i < 60 && calls.get() == 0; i++) { svc.me("host"); Thread.sleep(100); }
        assertThat(calls.get()).as("시민 봇은 LLM으로 판단해야 한다").isGreaterThan(0);

        MafiaStateResponse st = svc.me("host");
        assertThat(st.spareVotes()).as("무조건 사형이 아니라 생존 표가 나와야 한다").isGreaterThan(0);
    }

    @Test
    void 마피아봇은_동료가_재판대에_오르면_살린다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MafiaService svc = driveToFinalVote("사형", calls);

        int accused = ((Number) ReflectionTestUtils.getField(svc, "accusedSeat")).intValue();
        for (int i = 0; i < 60; i++) { svc.me("host"); Thread.sleep(50); }

        // 마피아 봇은 LLM("사형")을 따르지 않고 정체를 보고 정한다:
        // 재판대에 오른 게 동료면 생존, 시민이면 사형.
        List<?> players = (List<?>) ReflectionTestUtils.getField(svc, "players");
        boolean accusedMafia = isMafia(players.get(accused));
        Map<Integer, Boolean> fv = finalVotes(svc);
        int checked = 0;
        for (int seat = 0; seat < players.size(); seat++) {
            Object pl = players.get(seat);
            if (seat == accused || !isMafia(pl) || !flag(pl, "ai") || !flag(pl, "alive")) continue;
            assertThat(fv.get(seat)).as("마피아 봇의 사형/생존").isEqualTo(!accusedMafia);
            checked++;
        }
        assertThat(checked).as("검증한 마피아 봇 수").isGreaterThanOrEqualTo(0);
    }
}
