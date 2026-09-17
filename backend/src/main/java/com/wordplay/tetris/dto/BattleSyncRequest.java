package com.wordplay.tetris.dto;

/**
 * 플레이 중 클라이언트 보고.
 * attacks: 이번에 상대에게 보낼 가비지 줄 수(내 상쇄 처리 후 남은 공격).
 * heights: 내 보드 10칸 열 높이(상대 미니보드용).
 * alive: 아직 살아있는가(top-out 시 false).
 */
public record BattleSyncRequest(Integer attacks, int[] heights, Boolean alive) {}
