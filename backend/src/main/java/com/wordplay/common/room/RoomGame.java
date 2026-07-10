package com.wordplay.common.room;

/** 방 목록/정리에 필요한 게임 상태 요약. 각 게임 상태 클래스가 구현한다. */
public interface RoomGame {
    String roomStatus();   // WAITING / PLAYING / ENDED
    int playerCount();
    String hostLabel();
    boolean isEnded();
    long lastActiveMs();

    /** 클라이언트가 방에서 나감. 대기방이면 목록에서 제거, 진행/종료 중이면 '떠남' 표시. */
    default void leave(String clientId) {}
}
