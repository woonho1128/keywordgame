package com.wordplay.halligalli;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.halligalli.dto.HalliGalliStateResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 할리갈리 방 관리 + SSE(실시간 브로드캐스트). */
@Slf4j
@Service
public class HalliGalliRoomManager {

    private static final long SSE_TIMEOUT_MS = 30 * 60_000L; // 30분

    private final RoomRegistry<HalliGalliGame> reg = new RoomRegistry<>();
    // roomCode → (clientId → emitter)
    private final Map<String, Map<String, SseEmitter>> emitters = new ConcurrentHashMap<>();

    public String create(String clientId, String nick) {
        HalliGalliGame game = new HalliGalliGame();
        game.newGame(clientId, nick);
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public HalliGalliGame require(String code) {
        HalliGalliGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public HalliGalliGame find(String code) { return reg.find(code); }

    public List<RoomSummary> list() { return reg.list(); }

    public void resetAll() { reg.clear(); emitters.clear(); }

    public boolean closeRoom(String code) {
        emitters.remove(code == null ? "" : code.toUpperCase());
        return reg.remove(code);
    }

    // ---------- SSE ----------

    public SseEmitter subscribe(String code, String clientId) {
        String key = code.toUpperCase();
        HalliGalliGame g = require(key);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Map<String, SseEmitter> roomMap = emitters.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        // 같은 기기의 기존 연결이 있으면 정리
        SseEmitter prev = roomMap.put(clientId, emitter);
        if (prev != null) { try { prev.complete(); } catch (Exception ignore) {} }

        emitter.onCompletion(() -> roomMap.remove(clientId, emitter));
        emitter.onTimeout(() -> { roomMap.remove(clientId, emitter); emitter.complete(); });
        emitter.onError(e -> roomMap.remove(clientId, emitter));

        // 초기 상태 즉시 전송
        try {
            emitter.send(SseEmitter.event().name("state").data(g.me(clientId)));
        } catch (IOException e) {
            roomMap.remove(clientId, emitter);
        }
        return emitter;
    }

    /** 방의 모든 구독자에게 각자 관점의 상태를 즉시 푸시. */
    public void broadcast(String code) {
        String key = code.toUpperCase();
        Map<String, SseEmitter> roomMap = emitters.get(key);
        HalliGalliGame g = reg.find(key);
        if (roomMap == null || g == null) return;
        for (var e : roomMap.entrySet()) {
            String clientId = e.getKey();
            SseEmitter em = e.getValue();
            try {
                HalliGalliStateResponse state = g.me(clientId);
                em.send(SseEmitter.event().name("state").data(state));
            } catch (Exception ex) {
                roomMap.remove(clientId, em);
                try { em.complete(); } catch (Exception ignore) {}
            }
        }
    }
}
