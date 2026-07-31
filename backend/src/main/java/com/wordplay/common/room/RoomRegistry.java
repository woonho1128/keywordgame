package com.wordplay.common.room;

import com.wordplay.common.dto.RoomSummary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 방 코드 → 게임 인스턴스 레지스트리. 모드별로 하나씩 소유한다(인메모리).
 * 오래 방치되거나 종료된 방은 자동 정리한다.
 *
 * 전역 인덱스(GLOBAL): 코드 → 게임 키(프론트 경로). 코드를 전역에서 유일하게 발급하고,
 * "코드만으로 어느 게임 방인지" 조회(메인에서 코드로 바로 입장)를 지원한다.
 */
public class RoomRegistry<T extends RoomGame> {

    private static final int MAX_ROOMS = 100;
    private static final long IDLE_MS = 30 * 60_000L;       // 30분 방치 → 제거
    private static final long ENDED_IDLE_MS = 40_000L;      // 종료 후 40초 → 제거
    private static final long EMPTY_IDLE_MS = 20_000L;      // 빈 방 20초 → 제거
    /**
     * 3분간 이 방으로 아무 요청도 없으면 제거. 방 화면은 열려 있는 동안 1초 간격으로
     * me()를 호출하므로, 요청이 끊겼다는 건 아무도 그 방을 보고 있지 않다는 뜻이다.
     * (나가기를 누르지 않고 홈으로 가거나 탭을 닫아 '진행중'으로 남던 방을 정리한다.)
     */
    private static final long ABANDONED_MS = 3 * 60_000L;

    /** 코드(대문자) → 게임 키. 모든 레지스트리가 공유. */
    private static final Map<String, String> GLOBAL = new ConcurrentHashMap<>();

    /** 코드가 속한 게임 키 반환(없으면 null). */
    public static String resolveGame(String code) {
        return code == null ? null : GLOBAL.get(code.toUpperCase());
    }

    private final String gameKey;
    private final Map<String, T> rooms = new HashMap<>();
    /** 코드 → 마지막으로 이 방에 요청이 닿은 시각. 게임 구현과 무관하게 레지스트리가 직접 기록한다. */
    private final Map<String, Long> lastAccess = new HashMap<>();

    /** gameKey는 프론트 경로(예: "othello", "mafia-jobs", "yacht"). */
    public RoomRegistry(String gameKey) { this.gameKey = gameKey; }

    public synchronized String add(T game) {
        purge();
        if (rooms.size() >= MAX_ROOMS) throw new IllegalStateException("방이 너무 많습니다. 잠시 후 다시 시도하세요.");
        String code = uniqueCode();
        rooms.put(code, game);
        lastAccess.put(code, System.currentTimeMillis());
        GLOBAL.put(code, gameKey);
        return code;
    }

    /** 없으면 null. 조회 시각을 기록해 '아무도 보고 있지 않은 방' 판정에 쓴다. */
    public synchronized T find(String code) {
        if (code == null) return null;
        String u = code.toUpperCase();
        T g = rooms.get(u);
        if (g != null) lastAccess.put(u, System.currentTimeMillis());
        return g;
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
        for (String c : rooms.keySet()) GLOBAL.remove(c);
        rooms.clear();
        lastAccess.clear();
    }

    /** 특정 방 제거. 실제로 지웠으면 true. */
    public synchronized boolean remove(String code) {
        if (code == null) return false;
        String u = code.toUpperCase();
        boolean removed = rooms.remove(u) != null;
        lastAccess.remove(u);
        if (removed) GLOBAL.remove(u);
        return removed;
    }

    /** 클라이언트를 방에서 내보내고, 남은 인원이 0이면 방을 즉시 제거한다. */
    public synchronized void leave(String code, String clientId) {
        T g = find(code);
        if (g == null) return;
        g.leave(clientId);
        if (g.playerCount() == 0) remove(code);
        purge();
    }

    private void purge() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, T>> it = rooms.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, T> entry = it.next();
            T g = entry.getValue();
            long idle = now - g.lastActiveMs();
            long unseen = now - lastAccess.getOrDefault(entry.getKey(), now);
            boolean dead = idle > IDLE_MS
                    || unseen > ABANDONED_MS
                    || (g.isEnded() && idle > ENDED_IDLE_MS)
                    || (g.playerCount() == 0 && idle > EMPTY_IDLE_MS);
            if (dead) { it.remove(); lastAccess.remove(entry.getKey()); GLOBAL.remove(entry.getKey()); }
        }
    }

    private String uniqueCode() {
        String code;
        int guard = 0;
        do {
            code = randomCode();
        } while ((rooms.containsKey(code) || GLOBAL.containsKey(code)) && guard++ < 200);
        return code;
    }

    private static String randomCode() {
        char[] c = new char[4];
        for (int i = 0; i < 4; i++) c[i] = (char) ('A' + ThreadLocalRandom.current().nextInt(26));
        return new String(c);
    }
}
