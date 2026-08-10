package com.wordplay.snakes.dto;

import java.util.List;

/**
 * 뱀과 사다리 상태 응답.
 *
 * <p>보드는 매판 새로 만들어지므로 사다리·뱀 위치를 그대로 내려준다. 숨길 정보가 아니다 —
 * 실물 보드도 모두가 본다. 화면은 이 좌표로 SVG를 그린다.
 */
public record SnakesState(
        String phase,                 // LOBBY / PLAYING / ENDED
        int cols,                     // 한 줄 칸 수(10/8/7). 전체 칸은 cols*cols
        int size,                     // 도착 칸 번호
        long boardSeed,               // 이 판 보드의 시드(재현·문의 추적용)
        int turnSec,                  // 차례 제한시간(초). 0이면 제한 없음
        boolean noTimeLimit,
        boolean isHost,
        boolean joined,
        List<PlayerView> players,
        List<JumpView> ladders,
        List<JumpView> snakes,
        int turnSeat,
        String turnName,
        int nextSeat,                 // 다음 차례(나간 사람 건너뜀). 없으면 -1
        boolean myTurn,
        int mySeat,
        int lastDie,                  // 마지막으로 나온 눈(연출용). 없으면 0
        LastMove lastMove,            // 직전 이동(애니메이션용). 없으면 null
        String lastAction,
        List<String> log,
        int winnerSeat,
        String winnerLabel,
        long deadline,
        long serverNow
) {
    /** 방이 사라졌을 때. 클라이언트가 목록으로 돌아가게 한다. */
    public static SnakesState notFound(long now) {
        return new SnakesState("NONE", 10, 100, 0L, 0, false, false, false,
                List.of(), List.of(), List.of(), -1, null, -1, false, -1,
                0, null, null, List.of(), -1, null, 0, now);
    }

    public record PlayerView(int seat, String name, boolean bot, boolean host, boolean me,
                             boolean left, int pos, int rolls) {}

    /** 사다리·뱀 하나. from에 서면 to로 간다. */
    public record JumpView(int from, int to) {}

    /**
     * 직전 이동. 화면은 from → landed 까지 칸을 밟아가며 움직인 뒤,
     * 뱀·사다리면 landed → to 로 미끄러지는 연출을 한다.
     *
     * @param how MOVE(그냥 이동) / LADDER / SNAKE / OVER(칸을 넘겨 제자리)
     */
    public record LastMove(int seat, int die, int from, int landed, int to, String how) {}
}
