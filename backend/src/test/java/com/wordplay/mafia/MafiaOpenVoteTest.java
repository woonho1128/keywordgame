package com.wordplay.mafia;

import com.wordplay.mafia.dto.MafiaStateResponse;
import com.wordplay.mafia.dto.NewMafiaRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공개 투표.
 *
 * <p>봇 프롬프트에는 "누가 누구를 찍었나"가 들어가는데 사람은 집계("○○ 2표")만 볼 수
 * 있었다. 봇만 아는 정보가 되어 불공정했다. 개인별 기록을 모두에게 똑같이 보여준다.
 */
class MafiaOpenVoteTest {

    private static MafiaService votingRoom() {
        MafiaService svc = new MafiaService();
        svc.newGame("host", new NewMafiaRequest("방장", null, null, null, null, null, null, null));
        for (int i = 1; i < 4; i++) svc.join("p" + i, "사람" + i);
        svc.start("host");
        ReflectionTestUtils.setField(svc, "phase", MafiaService.Phase.VOTE);
        ReflectionTestUtils.setField(svc, "phaseEndsAt", System.currentTimeMillis() + 600_000L);
        return svc;
    }

    @Test
    void 누가_누구를_찍었는지_모두에게_보인다() {
        MafiaService svc = votingRoom();
        svc.vote("host", 2);    // 방장(좌석1) → 사람1(좌석2)
        svc.vote("p1", 3);      // 사람1(좌석2) → 사람2(좌석3)

        List<MafiaStateResponse.VoteCast> casts = svc.me("p2").voteCasts();
        assertThat(casts).isNotEmpty();
        // 남의 표도 보여야 한다(내 표만 보이면 의미가 없다)
        assertThat(casts).anyMatch(c -> c.voterSeat() == 1);
    }

    @Test
    void 기권은_기권으로_표시된다() {
        MafiaService svc = votingRoom();
        svc.vote("host", -1);

        assertThat(svc.me("p1").voteCasts())
                .anyMatch(c -> c.voterSeat() == 1 && c.targetSeat() == -1);
    }

    @Test
    void 투표_단계가_아니면_공개하지_않는다() {
        MafiaService svc = votingRoom();
        svc.vote("host", 2);
        ReflectionTestUtils.setField(svc, "phase", MafiaService.Phase.DISCUSS);

        assertThat(svc.me("host").voteCasts()).isEmpty();
    }

    @Test
    void 진행_이력에도_개인별_기록이_남는다() {
        MafiaService svc = votingRoom();
        svc.vote("host", 2);
        svc.vote("p1", 3);
        svc.vote("p2", 2);
        ReflectionTestUtils.invokeMethod(svc, "resolveNomination");

        @SuppressWarnings("unchecked")
        List<String> history = (List<String>) ReflectionTestUtils.getField(svc, "history");
        assertThat(history).anyMatch(h -> h.contains("🗳️ 투표:") && h.contains("←"));
    }

    @Test
    void 이력과_봇_프롬프트가_같은_문장을_쓴다() {
        MafiaService svc = votingRoom();
        svc.vote("host", 2);
        svc.vote("p2", 2);
        ReflectionTestUtils.invokeMethod(svc, "resolveNomination");

        List<?> players = (List<?>) ReflectionTestUtils.getField(svc, "players");
        String ctx = (String) ReflectionTestUtils.invokeMethod(svc, "chatContext", players.get(0));
        @SuppressWarnings("unchecked")
        List<String> history = (List<String>) ReflectionTestUtils.getField(svc, "history");
        String line = history.stream().filter(h -> h.contains("🗳️ 투표:")).findFirst().orElseThrow();
        String detail = line.substring(line.indexOf("투표:") + 3).trim();

        assertThat(ctx).as("봇이 보는 기록과 사람이 보는 이력이 같아야 한다").contains(detail);
    }
}
