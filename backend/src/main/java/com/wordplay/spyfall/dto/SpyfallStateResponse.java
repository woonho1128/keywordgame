package com.wordplay.spyfall.dto;

/**
 * 스파이폴 상태/역할 응답.
 * status:
 *   OK          — 이 기기에 좌석/역할이 배정됨 (isSpy, location, role 유효)
 *   NOT_STARTED — 아직 시작된 판이 없음 (새로고침으로 새 판 생성 필요)
 *   NOT_JOINED  — 판은 있으나 이 기기가 아직 좌석을 잡지 않음 (join 필요)
 *   FULL        — 좌석이 모두 찼음
 */
public record SpyfallStateResponse(
        String status,
        long round,
        int seat,          // 1-based, 미배정 시 0
        boolean isSpy,
        String location,   // 스파이면 null
        String role,       // 스파이면 null
        int playerCount,
        int spyCount,
        int joinedCount
) {
    public static SpyfallStateResponse notStarted() {
        return new SpyfallStateResponse("NOT_STARTED", 0, 0, false, null, null, 0, 0, 0);
    }
}
