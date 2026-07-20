package com.wordplay.yut;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.yut.dto.YutState;
import com.wordplay.yut.dto.YutState.Dest;
import com.wordplay.yut.dto.YutState.Move;
import com.wordplay.yut.dto.YutState.PlayerView;
import com.wordplay.yut.dto.YutState.ThrowResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 윷놀이 멀티플레이 방. 2~4인(+봇), 개인전/팀전(2:2), 백도 포함.
 *
 * 말판: 외곽 20칸(o0~o19, o0=출발·도착) + 대각 지름길(a·b, 중앙 ct).
 * 큰 원(o5·o10·ct)에 정확히 멈추면 다음 차례에 지름길/직진을 선택.
 * 잡기(다른 팀 말 원점)·윷·모는 한 번 더 던짐. 같은 팀 말은 업어서 함께 이동.
 * 먼저 말 4개(팀전은 팀 전체)를 다 빼내면 승리.
 */
public class YutGame implements RoomGame {

    public enum Phase { LOBBY, PLAYING, ENDED }

    static final int MAX_PLAYERS = 4, TOKENS = 4;
    static final long TURN_MS = 90_000, BOT_DELAY_MS = 1100;
    static final String WAIT = "wait", DONE = "done";

    // ── 이동 그래프 ──
    static final Map<String, String> NXT = new HashMap<>();
    static {
        NXT.put(WAIT, "o0");
        for (int i = 0; i < 19; i++) NXT.put("o" + i, "o" + (i + 1));
        NXT.put("o19", DONE);
        NXT.put("a1", "a2"); NXT.put("a2", "ct"); NXT.put("a3", "a4"); NXT.put("a4", "o15");
        NXT.put("b1", "b2"); NXT.put("b2", "ct"); NXT.put("b3", "b4"); NXT.put("b4", DONE);
    }
    private static String straight(String cell, String prev) {
        if ("ct".equals(cell)) return "a2".equals(prev) ? "a3" : "b3";
        return NXT.getOrDefault(cell, DONE);
    }
    /** 갈래길(큰 원)에서 시작 시 선택지(지름길 우선, 직진). 그 외는 1개. */
    private static List<String[]> firstOptions(String cell, String prev) {
        List<String[]> out = new ArrayList<>();
        switch (cell) {
            case "o5" -> { out.add(new String[]{"a1", cell}); out.add(new String[]{"o6", cell}); }
            case "o10" -> { out.add(new String[]{"b1", cell}); out.add(new String[]{"o11", cell}); }
            case "ct" -> { out.add(new String[]{"b3", cell}); out.add(new String[]{"a3", cell}); }
            default -> out.add(new String[]{straight(cell, prev), cell});
        }
        return out;
    }

    static final class Dst { final String cell, prev; Dst(String c, String p) { cell = c; prev = p; } }

    /** cell(prev에서 옴)에서 steps칸 전진했을 때 도착 후보(1~2개, 중복 제거). */
    private static List<Dst> forwardDests(String cell, String prev, int steps) {
        List<Dst> res = new ArrayList<>();
        for (String[] f : firstOptions(cell, prev)) {
            String cur = f[0], pv = f[1];
            for (int k = 1; k < steps; k++) {
                if (DONE.equals(cur)) break;
                String n = straight(cur, pv); pv = cur; cur = n;
            }
            boolean dup = false;
            for (Dst d : res) if (d.cell.equals(cur)) dup = true;
            if (!dup) res.add(new Dst(cur, pv));
        }
        return res;
    }

    // 백도용 역방향(주요 셀). 없으면 백도 불가.
    static final Map<String, String> PREV = new HashMap<>();
    static {
        for (int i = 1; i < 20; i++) PREV.put("o" + i, "o" + (i - 1));
        PREV.put("a2", "a1"); PREV.put("a4", "a3");
        PREV.put("b2", "b1"); PREV.put("b4", "b3");
        // ct, a1/a3, b1/b3, o15(대각 합류) 등은 백도 모호 → 저장된 token prev 사용
    }

    /** done까지 남은 대략 거리(봇 평가용, 지름길 우선). */
    private static int distToDone(String cell) {
        String cur = cell, prev = null; int d = 0, guard = 0;
        while (!DONE.equals(cur) && guard++ < 60) {
            String n = switch (cur) { case "o5" -> "a1"; case "o10" -> "b1"; case "ct" -> "b3"; default -> straight(cur, prev); };
            prev = cur; cur = n; d++;
        }
        return d;
    }

    static final class P {
        String clientId; String nick; boolean bot; String botLevel; boolean host; boolean left;
        int team, color;
        final String[] tok = new String[TOKENS];
        final String[] tokPrev = new String[TOKENS];
        int done = 0;
        long lastSeen;
        P() { for (int i = 0; i < TOKENS; i++) { tok[i] = WAIT; tokPrev[i] = null; } }
    }

    private final boolean teamMode, backDo;
    private Phase phase = Phase.LOBBY;
    private final String hostClientId;
    private final List<P> players = new ArrayList<>();
    private int turnSeat = -1, throwsOwed = 0;
    private final List<Integer> pending = new ArrayList<>();   // 던진 값(미사용)
    private int winnerTeam = -1;
    private String winnerLabel = null, lastAction = null;
    private long turnEndsAt = 0, botAt = 0, lastActive = System.currentTimeMillis();

    public YutGame(String hostClientId, String nick, boolean teamMode, boolean backDo) {
        this.hostClientId = hostClientId;
        this.teamMode = teamMode;
        this.backDo = backDo;
        P host = new P(); host.clientId = hostClientId; host.nick = clean(nick); host.host = true; host.lastSeen = now();
        players.add(host);
    }

    // ── 로비 ──
    public synchronized void join(String clientId, String nick) {
        touch();
        P e = byClient(clientId);
        if (e != null) { e.left = false; e.nick = clean(nick); return; }
        if (phase != Phase.LOBBY) throw bad("이미 시작된 방입니다");
        if (activeCount() >= MAX_PLAYERS) throw bad("정원(4명)이 찼습니다");
        P p = new P(); p.clientId = clientId; p.nick = clean(nick); p.lastSeen = now();
        players.add(p);
    }
    public synchronized void addBot(String clientId, String level) {
        touch(); requireHost(clientId);
        if (phase != Phase.LOBBY) throw bad("이미 시작된 방입니다");
        if (activeCount() >= MAX_PLAYERS) throw bad("정원(4명)이 찼습니다");
        P b = new P(); b.bot = true; b.botLevel = normLevel(level); b.nick = botName();
        players.add(b);
    }
    public synchronized void start(String clientId) {
        touch(); requireHost(clientId);
        if (phase != Phase.LOBBY) throw bad("이미 시작되었습니다");
        int n = activeCount();
        if (n < 2) throw bad("최소 2명(봇 포함)이 필요합니다");
        if (teamMode && n != 4) throw bad("팀전은 4명이 필요합니다");
        for (int i = 0; i < players.size(); i++) {
            P p = players.get(i);
            p.team = teamMode ? (i % 2) : i;
            p.color = i;
            p.done = 0;
            for (int t = 0; t < TOKENS; t++) { p.tok[t] = WAIT; p.tokPrev[t] = null; }
        }
        phase = Phase.PLAYING;
        winnerTeam = -1; winnerLabel = null;
        turnSeat = 0;
        beginTurn();
    }

    private void beginTurn() {
        throwsOwed = 1; pending.clear();
        turnEndsAt = now() + TURN_MS; botAt = now() + BOT_DELAY_MS;
    }

    // ── 던지기(파워 게이지) ──
    // power 0~120: 약하면 도/개, 알맞으면 걸/윷, 강하면 모, 과도(≥106)하면 낙(허탕).
    public synchronized void throwYut(String clientId, int power) {
        touch(); tick();
        requireTurn(clientId);
        if (throwsOwed <= 0) throw bad("먼저 말을 이동하세요");
        doThrow(players.get(turnSeat), power);
    }

    // 파워 유효 구간(이 밖이면 낙). 안이면 도개걸윷모는 실제 윷처럼 랜덤.
    static final int POWER_MIN = 25, POWER_MAX = 100;

    private void doThrow(P p, int power) {
        power = Math.max(0, Math.min(120, power));
        botAt = now() + BOT_DELAY_MS; turnEndsAt = now() + TURN_MS;
        if (power < POWER_MIN || power > POWER_MAX) { // 낙: 너무 약하거나 세게 던짐
            throwsOwed--;
            lastAction = p.nick + " ▸ 낙! (" + (power < POWER_MIN ? "너무 약하게" : "너무 세게") + " 던짐)";
            maybeAutoEnd(p);
            return;
        }
        // 유효 구간 → 윷짝 4개 랜덤(세기와 무관하게 결과는 운)
        boolean[] flat = new boolean[4];
        int flats = 0;
        for (int i = 0; i < 4; i++) { flat[i] = ThreadLocalRandom.current().nextBoolean(); if (flat[i]) flats++; }
        int value; boolean extra = false;
        if (flats == 0) { value = 5; extra = true; }          // 모
        else if (flats == 4) { value = 4; extra = true; }     // 윷
        else if (flats == 1) { value = (backDo && flat[0]) ? -1 : 1; } // 백도/도
        else if (flats == 2) { value = 2; }                   // 개
        else { value = 3; }                                   // 걸
        throwsOwed--;
        if (extra) throwsOwed++;
        pending.add(value);
        lastAction = p.nick + " ▸ " + nameOf(value) + (extra ? " (한 번 더!)" : "");
        maybeAutoEnd(p);
    }

    /** 봇 파워: 난이도가 높을수록 유효 구간(25~100)을 잘 맞춰 낙이 적음(결과는 여전히 랜덤). */
    private int botPower(P p) {
        return switch (p.botLevel == null ? "NORMAL" : p.botLevel) {
            case "HARD" -> 32 + ThreadLocalRandom.current().nextInt(60);   // 32~91 (거의 유효)
            case "EASY" -> 8 + ThreadLocalRandom.current().nextInt(112);   // 8~119 (가끔 낙)
            default -> 20 + ThreadLocalRandom.current().nextInt(90);       // 20~109 (가끔 낙)
        };
    }

    /** 던지기 다 끝났는데(빚 0) 쓸 수 있는 이동이 하나도 없으면 자동 종료. */
    private void maybeAutoEnd(P p) {
        if (throwsOwed > 0) return;
        if (pending.isEmpty()) { endTurn(); return; }
        if (legalMoves(p).isEmpty()) { pending.clear(); lastAction = p.nick + " ▸ 둘 수 없어 차례 넘김"; endTurn(); }
    }

    // ── 이동 ──
    public synchronized void move(String clientId, int value, int tokenIndex, String destCell) {
        touch(); tick();
        requireTurn(clientId);
        P p = players.get(turnSeat);
        if (!pending.contains(Integer.valueOf(value))) throw bad("사용할 수 없는 값입니다");
        Move chosen = null; Dest chosenDest = null;
        for (Move m : legalMoves(p)) {
            if (m.value() == value && m.tokenIndex() == tokenIndex) {
                for (Dest d : m.dests()) if (d.cell().equals(destCell)) { chosen = m; chosenDest = d; }
            }
        }
        if (chosen == null || chosenDest == null) throw bad("불가능한 이동입니다");
        applyMove(p, value, tokenIndex, chosenDest);
    }

    private void applyMove(P p, int value, int tokenIndex, Dest dest) {
        pending.remove(Integer.valueOf(value));
        String from = p.tok[tokenIndex];
        // 대기 말이 진입할 땐 그 말 하나만, 판 위 말은 같은 팀 그룹(업기)이 함께 이동
        List<int[]> group = WAIT.equals(from) ? List.of(new int[]{turnSeat, tokenIndex}) : groupAt(p.team, from);
        boolean finish = DONE.equals(dest.cell());
        for (int[] g : group) {
            P owner = players.get(g[0]);
            if (finish) { owner.tok[g[1]] = DONE; owner.tokPrev[g[1]] = null; owner.done++; }
            else { owner.tokPrev[g[1]] = from; owner.tok[g[1]] = dest.cell(); }
        }
        boolean caught = false;
        if (!finish) caught = resolveCatch(p.team, dest.cell());
        int cnt = group.size();
        lastAction = p.nick + " ▸ " + nameOf(value) + (cnt > 1 ? " (" + cnt + "말 업기)" : "")
                + (finish ? " · 도착!" : caught ? " · 잡았다! 한 번 더" : "");
        if (caught) throwsOwed++;
        if (checkWin(p.team)) { endGame(p.team); return; }
        maybeAutoEnd(p);
    }

    /** dest 도착 시 다른 팀 말이 있으면 원점 복귀. 잡았으면 true. */
    private boolean resolveCatch(int team, String cell) {
        if (WAIT.equals(cell) || DONE.equals(cell)) return false;
        boolean caught = false;
        for (P q : players) {
            if (q.team == team || q.left) continue;
            for (int t = 0; t < TOKENS; t++) if (cell.equals(q.tok[t])) { q.tok[t] = WAIT; q.tokPrev[t] = null; caught = true; }
        }
        return caught;
    }

    private List<int[]> groupAt(int team, String cell) {
        List<int[]> out = new ArrayList<>();
        if (WAIT.equals(cell) || DONE.equals(cell)) return out;
        for (int s = 0; s < players.size(); s++) {
            P q = players.get(s);
            if (q.team != team) continue;
            for (int t = 0; t < TOKENS; t++) if (cell.equals(q.tok[t])) out.add(new int[]{s, t});
        }
        return out;
    }

    private String backPrev(String cell) { return PREV.get(cell); }

    private boolean checkWin(int team) {
        for (P q : players) if (!q.left && q.team == team && q.done < TOKENS) return false;
        return true;
    }
    private void endGame(int team) {
        phase = Phase.ENDED; winnerTeam = team; turnEndsAt = 0;
        List<String> names = new ArrayList<>();
        for (P q : players) if (!q.left && q.team == team) names.add(q.nick);
        winnerLabel = teamMode ? ("팀 " + String.join("·", names)) : (names.isEmpty() ? "" : names.get(0));
        lastAction = winnerLabel + " 승리!";
    }

    private void endTurn() {
        int n = players.size();
        for (int step = 1; step <= n; step++) {
            int s = (turnSeat + step) % n;
            if (!players.get(s).left) { turnSeat = s; beginTurn(); return; }
        }
        beginTurn();
    }

    // ── 합법 이동 목록 ──
    private List<Move> legalMoves(P p) {
        List<Move> out = new ArrayList<>();
        // 값 종류별 1개씩만(같은 값 여러 개 있어도 표시는 1)
        List<Integer> vals = new ArrayList<>();
        for (int v : pending) if (!vals.contains(v)) vals.add(v);
        for (int v : vals) {
            for (int t = 0; t < TOKENS; t++) {
                String cell = p.tok[t];
                if (DONE.equals(cell)) continue;
                List<Dest> dests = new ArrayList<>();
                if (v < 0) { // 백도
                    if (WAIT.equals(cell)) continue;
                    String bp = backPrev(cell);
                    String pv = p.tokPrev[t];
                    String target = bp != null ? bp : (pv != null && !WAIT.equals(pv) ? pv : null);
                    if (target == null) continue;
                    dests.add(new Dest(target, cellLabel(target), wouldCatch(p.team, target), false));
                } else {
                    String prev = WAIT.equals(cell) ? null : p.tokPrev[t];
                    for (Dst d : forwardDests(cell, prev == null ? "" : prev, v)) {
                        boolean fin = DONE.equals(d.cell);
                        dests.add(new Dest(d.cell, fin ? "도착" : cellLabel(d.cell), !fin && wouldCatch(p.team, d.cell), fin));
                    }
                }
                if (!dests.isEmpty()) out.add(new Move(v, nameOf(v), t, dests));
            }
        }
        // 같은 칸에 있는 내 말은 대표 1개만(업기) → tokenIndex 중복 칸 정리
        return dedupeByCell(p, out);
    }

    /** 같은 값+같은 칸(그룹)인 말은 대표 하나만 남김. */
    private List<Move> dedupeByCell(P p, List<Move> moves) {
        List<Move> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Move m : moves) {
            String key = m.value() + "@" + p.tok[m.tokenIndex()];
            if (seen.add(key)) out.add(m);
        }
        return out;
    }

    private boolean wouldCatch(int team, String cell) {
        if (WAIT.equals(cell) || DONE.equals(cell)) return false;
        for (P q : players) { if (q.team == team || q.left) continue; for (int t = 0; t < TOKENS; t++) if (cell.equals(q.tok[t])) return true; }
        return false;
    }

    // ── 봇/타임아웃 ──
    public synchronized void tick() {
        if (phase != Phase.PLAYING) return;
        if (turnSeat < 0) return;
        P cur = players.get(turnSeat);
        if (cur.left) { endTurn(); return; }
        long t = now();
        if (cur.bot) { if (t >= botAt) botStep(cur); }
        else if (t >= turnEndsAt) autoStep(cur);
    }

    private void botStep(P p) {
        int guard = 0;
        while (turnSeat == seatOf(p) && phase == Phase.PLAYING && guard++ < 60) {
            if (throwsOwed > 0) { doThrow(p, botPower(p)); continue; }
            if (pending.isEmpty()) break;
            if (!applyBestMove(p)) break;
        }
    }
    private void autoStep(P p) {
        int guard = 0;
        while (turnSeat == seatOf(p) && phase == Phase.PLAYING && guard++ < 60) {
            if (throwsOwed > 0) { doThrow(p, 40 + ThreadLocalRandom.current().nextInt(60)); continue; } // 시간초과 자동: 무난한 파워
            if (pending.isEmpty()) break;
            if (!applyBestMove(p)) break;
        }
    }

    /** 봇/자동: 최선 이동 하나 적용. 없으면 false. */
    private boolean applyBestMove(P p) {
        List<Move> moves = legalMoves(p);
        if (moves.isEmpty()) { pending.clear(); endTurn(); return false; }
        boolean easy = "EASY".equals(p.botLevel);
        Move bestM = null; Dest bestD = null; double best = -1e9;
        for (Move m : moves) for (Dest d : m.dests()) {
            double s = 0;
            if (d.finish()) s += 100;
            if (d.caught()) s += 60;
            s += (40 - distToDone(d.cell().equals(DONE) ? DONE : d.cell()));
            if (easy) s += ThreadLocalRandom.current().nextDouble(0, 30);
            if (s > best) { best = s; bestM = m; bestD = d; }
        }
        applyMove(p, bestM.value(), bestM.tokenIndex(), bestD);
        return true;
    }

    // ── 상태 뷰 ──
    public synchronized YutState me(String clientId) {
        touch(); tick();
        P me = byClient(clientId);
        int meSeat = me == null ? -1 : seatOf(me);

        List<PlayerView> pv = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            P p = players.get(i);
            if (p.left && phase == Phase.LOBBY) continue;
            List<String> toks = new ArrayList<>();
            for (int t = 0; t < TOKENS; t++) toks.add(p.tok[t]);
            pv.add(new PlayerView(i, p.nick, p.bot, p.host, p == me, p.team, p.color, toks, p.done, p.left));
        }
        List<ThrowResult> pend = new ArrayList<>();
        for (int v : pending) pend.add(new ThrowResult(nameOf(v), v, v == 4 || v == 5));
        boolean myTurn = phase == Phase.PLAYING && meSeat == turnSeat && me != null && !me.left;
        List<Move> moves = myTurn && throwsOwed <= 0 || (myTurn && !pending.isEmpty()) ? legalMoves(me) : List.of();
        String turnName = phase == Phase.PLAYING && turnSeat >= 0 ? players.get(turnSeat).nick : null;

        return new YutState(
                phase.name(), teamMode, backDo,
                clientId != null && clientId.equals(hostClientId), me != null,
                pv, turnSeat, turnName, myTurn, meSeat, me == null ? -1 : me.team,
                myTurn ? throwsOwed : 0, myTurn ? pend : List.of(), moves,
                lastAction, winnerTeam, winnerLabel, turnEndsAt, now());
    }

    // ── RoomGame ──
    @Override public String roomStatus() { return switch (phase) { case LOBBY -> "WAITING"; case ENDED -> "ENDED"; default -> "PLAYING"; }; }
    @Override public int playerCount() { int n = 0; for (P p : players) if (!p.bot && !p.left) n++; return n; }
    @Override public String hostLabel() { for (P p : players) if (p.host) return p.nick; return "-"; }
    @Override public boolean isEnded() { return phase == Phase.ENDED; }
    @Override public long lastActiveMs() { return lastActive; }
    @Override public synchronized void leave(String clientId) {
        P p = byClient(clientId); if (p == null) return;
        if (phase == Phase.LOBBY) players.remove(p);
        else { p.left = true; if (phase == Phase.PLAYING && seatOf(p) == turnSeat) endTurn(); }
        touch();
    }

    // ── 조회/유틸 ──
    public synchronized Phase phase() { return phase; }
    public int winnerTeam() { return winnerTeam; }
    public int turnSeat() { return turnSeat; }
    public List<P> playersList() { return players; }
    public int seatOf(P p) { return players.indexOf(p); }
    public P byClient(String clientId) { if (clientId == null) return null; for (P p : players) if (clientId.equals(p.clientId)) return p; return null; }

    private void requireTurn(String clientId) {
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        P p = byClient(clientId);
        if (p == null || seatOf(p) != turnSeat) throw bad("당신 차례가 아닙니다");
    }
    private int activeCount() { int n = 0; for (P p : players) if (!p.left) n++; return n; }
    private void requireHost(String c) { if (!hostClientId.equals(c)) throw bad("방장만 할 수 있습니다"); }
    private String botName() { int n = 1; for (P p : players) if (p.bot) n++; return "봇" + n; }
    private void touch() { lastActive = now(); }
    private static long now() { return System.currentTimeMillis(); }
    private static String nameOf(int v) { return switch (v) { case -1 -> "백도"; case 1 -> "도"; case 2 -> "개"; case 3 -> "걸"; case 4 -> "윷"; case 5 -> "모"; default -> "?"; }; }
    private static String cellLabel(String c) { return c; }
    private static String clean(String s) { String n = s == null ? "" : s.trim(); if (n.isEmpty()) n = "익명"; return n.length() > 16 ? n.substring(0, 16) : n; }
    private static String normLevel(String s) { String u = s == null ? "NORMAL" : s.toUpperCase(); return switch (u) { case "EASY", "HARD", "NORMAL" -> u; default -> "NORMAL"; }; }
    private static BusinessException bad(String m) { return new BusinessException(ErrorCode.INVALID_INPUT, m); }

    // 테스트 헬퍼
    void speedUpBotsForTest() { botAt = 0; }
    List<Integer> pendingForTest() { return pending; }
    int throwsOwedForTest() { return throwsOwed; }
    void giveTurnForTest(int seat) { turnSeat = seat; beginTurn(); }
}
