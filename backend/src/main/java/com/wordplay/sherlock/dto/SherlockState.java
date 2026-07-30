package com.wordplay.sherlock.dto;

import java.util.List;

/** 셜록13 상태 응답. 내 손패(myCards)는 본인에게만 채워 보낸다. */
public record SherlockState(
        String phase,                 // LOBBY / PLAYING / ENDED
        int turnSec,                  // 턴 제한시간(초)
        boolean memoryMode,           // 정통 기억 모드(단서 로그 미보존)
        boolean isHost,
        boolean joined,
        List<PlayerView> players,
        List<CharView> deck,          // 이번 판 캐릭터(공개 참조표)
        List<Integer> myCards,        // 내 손패 캐릭터 id — 나만
        List<String> items,           // 8개 아이템 이름(이모지 포함)
        int turnSeat,
        String turnName,
        boolean myTurn,
        int mySeat,
        List<Clue> clues,             // 공개 단서(질문/답변)
        String lastAction,
        List<String> log,
        int winnerSeat,
        String winnerLabel,
        long deadline,
        long serverNow
) {
    public record PlayerView(int seat, String name, boolean bot, boolean host, boolean me,
                             boolean alive, boolean left, int cardCount) {}
    /** id = 마스터 캐릭터 인덱스, items = 아이템 인덱스 목록(공개). */
    public record CharView(int id, String name, List<Integer> items) {}
    /** target=-1 이면 전체 조사. results = 각 대상의 아이템 개수. */
    public record Clue(int askerSeat, String askerName, int target, int item, List<SeatCount> results) {}
    public record SeatCount(int seat, int count) {}

    public static SherlockState notFound(long now) {
        return new SherlockState("NONE", 60, false, false, false, List.of(), List.of(), List.of(), List.of(),
                -1, null, false, -1, List.of(), null, List.of(), -1, null, 0, now);
    }
}
