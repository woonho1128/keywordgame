package com.wordplay.tetris;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.tetris.dto.TetrisBattleState;
import com.wordplay.tetris.dto.TetrisBattleState.MeView;
import com.wordplay.tetris.dto.TetrisBattleState.OpponentView;
import com.wordplay.tetris.dto.TetrisBattleState.PlayerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 테트리스 배틀 방(1v1 / 배틀로얄).
 *
 * 각 사람은 자기 브라우저에서 게임을 로컬로 돌리고, ~1초마다 sync로 "보낼 공격량·보드 높이·생존"을
 * 보고한다. 서버는 공격(가비지 줄 수)을 생존 중인 상대에게 라우팅하고, 각자 받을 가비지를 큐에 쌓아
 * sync 응답으로 1회 전달한다. 보드 자체는 서버가 들지 않는다(클라 신뢰).
 * 봇은 서버측 추상 모델(TetrisBotSim 파라미터)로 굴린다.
 */
public class TetrisBattleGame implements RoomGame {

    public enum Format { DUEL, ROYALE }
    public enum Phase { LOBBY, PLAYING, ENDED }

    private static final int MAX_DUEL = 2;
    private static final int MAX_ROYALE = 6;
    private static final long STALEMATE_MS = 5 * 60_000L; // 5분 교착 → 판정

    static final class P {
        String clientId;      // 봇이면 null
        String nick;
        boolean bot;
        String botLevel;      // EASY/NORMAL/HARD
        boolean alive = true;
        boolean left = false;
        boolean host;
        int[] heights = new int[10];
        int pendingGarbage = 0; // 나에게 배정된(받을) 가비지. 사람=sync로 전달, 봇=tick에서 소비
        Integer placement = null;
        long lastSeenMs;
        // 봇 시뮬 상태
        double botStack = 0;
        double botAtkAcc = 0;
    }

    private final Format format;
    private Phase phase = Phase.LOBBY;
    private final String hostClientId;
    private final List<P> players = new ArrayList<>();
    private long startedMs = 0;
    private long lastActive = System.currentTimeMillis();
    private long lastBotTick = 0;
    private String winner = null;
    private boolean winRecorded = false;

    private TetrisBattleRankService rankService; // 우승 기록용(선택 주입)

    public TetrisBattleGame(String hostClientId, String nick, String format) {
        this.hostClientId = hostClientId;
        this.format = "ROYALE".equalsIgnoreCase(format) ? Format.ROYALE : Format.DUEL;
        P host = new P();
        host.clientId = hostClientId; host.nick = clean(nick); host.host = true;
        host.lastSeenMs = System.currentTimeMillis();
        players.add(host);
    }

    void setRankService(TetrisBattleRankService r) { this.rankService = r; }

    private int maxPlayers() { return format == Format.ROYALE ? MAX_ROYALE : MAX_DUEL; }

    // ── 로비 ────────────────────────────────────────────
    public synchronized TetrisBattleState join(String clientId, String nick) {
        touch();
        P existing = byClient(clientId);
        if (existing != null) { existing.left = false; return state(clientId); }
        if (phase != Phase.LOBBY) throw bad("이미 시작된 방입니다");
        if (activeCount() >= maxPlayers()) throw bad("정원이 가득 찼습니다");
        P p = new P();
        p.clientId = clientId; p.nick = clean(nick); p.lastSeenMs = System.currentTimeMillis();
        players.add(p);
        return state(clientId);
    }

    public synchronized TetrisBattleState addBot(String clientId, String level) {
        touch();
        requireHost(clientId);
        if (phase != Phase.LOBBY) throw bad("이미 시작된 방입니다");
        if (activeCount() >= maxPlayers()) throw bad("정원이 가득 찼습니다");
        P b = new P();
        b.bot = true; b.botLevel = normLevel(level);
        b.nick = botName();
        players.add(b);
        return state(clientId);
    }

    public synchronized TetrisBattleState start(String clientId) {
        touch();
        requireHost(clientId);
        if (phase != Phase.LOBBY) throw bad("이미 시작되었습니다");
        if (activeCount() < 2) throw bad("상대(봇 또는 유저)가 최소 1명 필요합니다");
        phase = Phase.PLAYING;
        startedMs = System.currentTimeMillis();
        lastBotTick = startedMs;
        for (P p : players) { p.alive = true; p.placement = null; p.pendingGarbage = 0; p.botStack = 0; p.botAtkAcc = 0; }
        return state(clientId);
    }

    // ── 플레이 중 동기화 ─────────────────────────────────
    public synchronized TetrisBattleState sync(String clientId, Integer attacks, int[] heights, Boolean alive) {
        touch();
        P me = byClient(clientId);
        if (me == null) return state(clientId);
        me.lastSeenMs = System.currentTimeMillis();
        if (phase == Phase.PLAYING) {
            if (heights != null && heights.length == 10) me.heights = heights.clone();
            if (attacks != null && attacks > 0 && me.alive) routeAttack(me, Math.min(attacks, 20));
            if (Boolean.FALSE.equals(alive) && me.alive) ko(me);
            tickBots();
            checkEnd();
        }
        // 받을 가비지 1회 전달(소비)
        int deliver = me.pendingGarbage;
        me.pendingGarbage = 0;
        return stateWithGarbage(clientId, deliver);
    }

    // ── 조회(로비/읽기) ─────────────────────────────────
    public synchronized TetrisBattleState me(String clientId) {
        touch();
        P me = byClient(clientId);
        if (me != null) me.lastSeenMs = System.currentTimeMillis();
        if (phase == Phase.PLAYING) { tickBots(); checkEnd(); }
        return state(clientId);
    }

    // ── 공격 라우팅 ─────────────────────────────────────
    private void routeAttack(P from, int amount) {
        List<P> targets = new ArrayList<>();
        for (P p : players) if (p != from && p.alive && !p.left) targets.add(p);
        if (targets.isEmpty()) return;
        P target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
        target.pendingGarbage += amount;
    }

    // ── 봇 시뮬 ─────────────────────────────────────────
    private void tickBots() {
        long now = System.currentTimeMillis();
        double dt = Math.min(2.0, (now - lastBotTick) / 1000.0);
        lastBotTick = now;
        if (dt <= 0) return;
        for (P b : players) {
            if (!b.bot || !b.alive) continue;
            double[] cfg = botCfg(b.botLevel); // [clearRate, attackEff, defense, koThreshold]
            // 받은 가비지 → 방어율 제외하고 스택 상승
            b.botStack += b.pendingGarbage * (1 - cfg[2]);
            b.pendingGarbage = 0;
            // 클리어 → 스택 하강 + 공격 생산
            double cleared = cfg[0] * dt;
            b.botStack = Math.max(0, b.botStack - cleared);
            b.botAtkAcc += cleared * cfg[1];
            while (b.botAtkAcc >= 1) { routeAttack(b, 1); b.botAtkAcc -= 1; }
            b.heights = profileFromStack(b.botStack);
            if (b.botStack >= cfg[3]) ko(b);
        }
    }

    private static double[] botCfg(String level) {
        return switch (level == null ? "NORMAL" : level) {
            case "EASY" -> new double[]{0.5, 0.30, 0.30, 16};
            case "HARD" -> new double[]{1.4, 0.70, 0.70, 20};
            default     -> new double[]{0.9, 0.50, 0.50, 18};
        };
    }

    private static int[] profileFromStack(double stack) {
        int base = (int) Math.floor(stack);
        int[] h = new int[10];
        for (int i = 0; i < 10; i++) {
            int v = base + ThreadLocalRandom.current().nextInt(-1, 2);
            h[i] = Math.max(0, Math.min(20, v));
        }
        return h;
    }

    // ── KO · 종료 ───────────────────────────────────────
    private void ko(P p) {
        if (!p.alive) return;
        p.alive = false;
        p.placement = aliveCount() + 1; // 방금 죽은 본인 포함한 등수
    }

    private void checkEnd() {
        if (phase != Phase.PLAYING) return;
        // 교착 판정: 5분 초과면 가장 안전한(스택/높이 낮은) 순으로 등수 부여
        if (System.currentTimeMillis() - startedMs > STALEMATE_MS && aliveCount() > 1) {
            List<P> alive = new ArrayList<>();
            for (P p : players) if (p.alive && !p.left) alive.add(p);
            alive.sort((a, b) -> Integer.compare(danger(a), danger(b))); // 낮은 위험 먼저(1등)
            for (int i = 0; i < alive.size(); i++) { alive.get(i).alive = false; alive.get(i).placement = i + 1; }
            finish(alive.isEmpty() ? null : alive.get(0));
            return;
        }
        if (aliveCount() <= 1) {
            P last = null;
            for (P p : players) if (p.alive && !p.left) { last = p; break; }
            if (last != null) last.placement = 1;
            finish(last);
        }
    }

    private void finish(P winnerP) {
        phase = Phase.ENDED;
        winner = winnerP == null ? null : winnerP.nick;
        if (!winRecorded && winnerP != null && !winnerP.bot && rankService != null) {
            winRecorded = true;
            try { rankService.recordWin(winnerP.nick); } catch (Exception ignored) {}
        }
    }

    private int danger(P p) {
        if (p.bot) return (int) Math.round(p.botStack);
        int s = 0; for (int h : p.heights) s += h; return s;
    }

    private int aliveCount() {
        int n = 0; for (P p : players) if (p.alive && !p.left) n++; return n;
    }

    // ── 상태 직렬화 ─────────────────────────────────────
    private TetrisBattleState state(String clientId) { return stateWithGarbage(clientId, 0); }

    private TetrisBattleState stateWithGarbage(String clientId, int incomingGarbage) {
        P me = byClient(clientId);
        List<PlayerView> pv = new ArrayList<>();
        for (P p : players) {
            if (p.left && phase == Phase.LOBBY) continue;
            pv.add(new PlayerView(p.nick, p.bot, p.botLevel, p.host, p == me, p.alive));
        }
        List<OpponentView> ov = new ArrayList<>();
        if (phase != Phase.LOBBY) {
            for (P p : players) {
                if (p == me || p.left) continue;
                ov.add(new OpponentView(p.nick, p.bot, p.alive, p.heights, p.placement));
            }
        }
        MeView mv = new MeView(me != null && me.alive, me == null ? null : me.placement, incomingGarbage);
        return new TetrisBattleState(
                phase.name(), null, format.name(),
                clientId != null && clientId.equals(hostClientId),
                me != null,
                pv, mv, ov,
                aliveCount(), activeCount(), winner,
                me == null ? null : me.placement,
                System.currentTimeMillis(), startedMs);
    }

    // ── RoomGame ────────────────────────────────────────
    @Override public String roomStatus() {
        return switch (phase) { case LOBBY -> "WAITING"; case PLAYING -> "PLAYING"; default -> "ENDED"; };
    }
    @Override public int playerCount() {
        int n = 0; for (P p : players) if (!p.bot && !p.left) n++; return n; // 사람 기준(빈 방 정리용)
    }
    @Override public String hostLabel() {
        for (P p : players) if (p.host) return p.nick;
        return "-";
    }
    @Override public boolean isEnded() { return phase == Phase.ENDED; }
    @Override public long lastActiveMs() { return lastActive; }
    @Override public synchronized void leave(String clientId) {
        P p = byClient(clientId);
        if (p == null) return;
        p.left = true;
        if (phase == Phase.LOBBY) {
            players.remove(p);
        } else if (phase == Phase.PLAYING && p.alive) {
            ko(p); checkEnd();
        }
        touch();
    }

    // ── 유틸 ────────────────────────────────────────────
    private int activeCount() { int n = 0; for (P p : players) if (!p.left) n++; return n; }
    private P byClient(String clientId) {
        if (clientId == null) return null;
        for (P p : players) if (clientId.equals(p.clientId)) return p;
        return null;
    }
    private void requireHost(String clientId) {
        if (!hostClientId.equals(clientId)) throw bad("방장만 할 수 있습니다");
    }
    private String botName() {
        int n = 1; for (P p : players) if (p.bot) n++;
        return "봇" + n;
    }
    private void touch() { lastActive = System.currentTimeMillis(); }
    private static String clean(String s) {
        String n = s == null ? "" : s.trim();
        if (n.isEmpty()) n = "익명";
        return n.length() > 16 ? n.substring(0, 16) : n;
    }
    private static String normLevel(String s) {
        String u = s == null ? "NORMAL" : s.toUpperCase();
        return switch (u) { case "EASY", "HARD", "NORMAL" -> u; default -> "NORMAL"; };
    }
    private static BusinessException bad(String m) { return new BusinessException(ErrorCode.INVALID_INPUT, m); }
}
