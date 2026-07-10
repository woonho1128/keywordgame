package com.wordplay.common.room;

import com.wordplay.common.dto.RoomSummary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 방 코드 → 게임 인스턴스 레지스트리. 모드별로 하나씩 소유한다(인메모리).
 * 오래 방치되거나 종료된 방은 자동 정리한다.
 */
public class RoomRegistry<T extends RoomGame> {

    private static final int MAX_ROOMS = 100;
    private static final long IDLE_MS = 30 * 60_000L;       // 30분 방치 → 제거
    private static final long ENDED_IDLE_MS = 40_000L;      // 종료 후 40초 → 제거
    private static final long EMPTY_IDLE_MS = 20_000L;      // 빈 방 20초 → 제거

    private final Map<String, T> rooms = new HashMap<>();

    public synchronized String add(T game) {
        purge();
        if (rooms.size() >= MAX_ROOMS) throw new IllegalStateException("방이 너무 많습니다. 잠시 후 다시 시도하세요.");
        String code = uniqueCode();
        rooms.put(code, game);
        return code;
    }

    /** 없으면 null. */
    public synchronized T find(String code) {
        return code == null ? null : rooms.get(code.toUpperCase());
    }

    public synchronized List<RoomSummary> list() {
        purge();
        List<RoomSummary> out = new ArrayList<>();
        for (var e : rooms.entrySet()) {
            T g = e.getValue();
            out.add(new RoomSummary(e.getKey(), g.roomStatus(), g.playerCount(), g.hostLabel()));
        }
        // 모집중 먼저, 그다음 진행중, 종료 마지막
        out.sort(Comparator.comparingInt(r -> switch (r.status()) {
            case "WAITING" -> 0;
            case "PLAYING" -> 1;
            default -> 2;
        }));
        return out;
    }

    public synchronized void clear() {
        rooms.clear();
    }

    /** 특정 방 제거. 실제로 지웠으면 true. */
    public synchronized boolean remove(String code) {
        return code != null && rooms.remove(code.toUpperCase()) != null;
    }

    /** 클라이언트를 방에서 내보내고, 남은 인원이 0이면 방을 즉시 제거한다. */
    public synchronized void leave(String code, String clientId) {
        T g = find(code);
        if (g == null) return;
        g.leave(clientId);
        if (g.playerCount() == 0) rooms.remove(code.toUpperCase());
        purge();
    }

    private void purge() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, T>> it = rooms.entrySet().iterator();
        while (it.hasNext()) {
            T g = it.next().getValue();
            long idle = now - g.lastActiveMs();
            boolean dead = idle > IDLE_MS
                    || (g.isEnded() && idle > ENDED_IDLE_MS)
                    || (g.playerCount() == 0 && idle > EMPTY_IDLE_MS);
            if (dead) it.remove();
        }
    }

    private String uniqueCode() {
        String code;
        int guard = 0;
        do {
            code = randomCode();
        } while (rooms.containsKey(code) && guard++ < 50);
        return code;
    }

    private static String randomCode() {
        char[] c = new char[4];
        for (int i = 0; i < 4; i++) c[i] = (char) ('A' + ThreadLocalRandom.current().nextInt(26));
        return new String(c);
    }
}
