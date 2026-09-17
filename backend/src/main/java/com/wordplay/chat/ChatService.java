package com.wordplay.chat;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 게임 로직과 독립된 방 단위 실시간 채팅. 키 = game + ":" + roomCode.
 * 인메모리·폴링. 방마다 최근 N개만 보관하고, 오래 쓰지 않은 방은 정리한다.
 */
@Service
public class ChatService {

    public record Msg(long seq, String nick, String text, long ts, String senderId) {}

    private static final int MAX_MSGS = 120;
    private static final long IDLE_TTL_MS = 3 * 60 * 60 * 1000L; // 3시간

    private static final class Room {
        final List<Msg> msgs = new ArrayList<>();
        long seq = 0;
        long lastActive = System.currentTimeMillis();
    }

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    private static String key(String game, String roomCode) { return game + ":" + roomCode; }

    public synchronized long send(String game, String roomCode, String senderId, String nick, String text) {
        purgeIdle();
        Room r = rooms.computeIfAbsent(key(game, roomCode), k -> new Room());
        r.seq++;
        r.msgs.add(new Msg(r.seq, trim(nick, 16, "익명"), trim(text, 300, ""), System.currentTimeMillis(), senderId));
        if (r.msgs.size() > MAX_MSGS) r.msgs.remove(0);
        r.lastActive = System.currentTimeMillis();
        return r.seq;
    }

    public synchronized List<Msg> since(String game, String roomCode, long since) {
        Room r = rooms.get(key(game, roomCode));
        if (r == null) return List.of();
        r.lastActive = System.currentTimeMillis();
        List<Msg> out = new ArrayList<>();
        for (Msg m : r.msgs) if (m.seq() > since) out.add(m);
        return out;
    }

    private void purgeIdle() {
        long now = System.currentTimeMillis();
        rooms.entrySet().removeIf(e -> now - e.getValue().lastActive > IDLE_TTL_MS);
    }

    private static String trim(String s, int max, String fallback) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) return fallback;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
