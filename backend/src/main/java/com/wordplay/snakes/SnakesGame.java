package com.wordplay.snakes;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.snakes.dto.SnakesState;
import com.wordplay.snakes.dto.SnakesState.JumpView;
import com.wordplay.snakes.dto.SnakesState.LastMove;
import com.wordplay.snakes.dto.SnakesState.PlayerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 뱀과 사다리. 2~10인(+봇). 정통 턴제.
 *
 * <p>차례가 된 사람이 주사위를 굴려 그만큼 전진한다. 사다리 밑에 서면 위로 올라가고,
 * 뱀 머리를 밟으면 꼬리로 미끄러진다. 6이 나오면 한 번 더 굴리되 연속
 * {@value #MAX_EXTRA_ROLLS}회까지만 — 무한히 도는 턴을 막는다. 마지막 칸에 정확히
 * 떨어져야 승리하고, 넘치면 제자리다.
 *
 * <p>보드는 {@link SnakesBoard}가 매판 새로 만든다. 인원이 늘수록 판을 줄여
 * (10×10 → 8×8 → 7×7) 자기 차례를 기다리는 시간이 과하게 늘지 않게 한다.
 *
 * <p>봇은 규칙 기반이다 — 주사위를 굴리는 것 외에 판단할 게 없어서 LLM이 필요 없다.
 */
public class SnakesGame implements RoomGame {

    public enum Phase { LOBBY, PLAYING, ENDED }

    static final int MAX_PLAYERS = 10;
    /**
     * 봇이 굴리기까지 기다리는 시간.
     *
     * <p>화면에서 주사위가 구르고(0.7초) 말이 한 칸씩 이동하고(칸당 0.16초, 최대 6칸)
     * 뱀·사다리를 타는(0.7초) 연출이 끝날 시간을 줘야 한다. 짧으면 연출이 겹쳐
     * 무슨 일이 일어났는지 볼 수 없다.
     */
    static final long BOT_DELAY_MS = 2800;
    /** 6이 연달아 나올 때 추가로 굴릴 수 있는 최대 횟수. */
    static final int MAX_EXTRA_ROLLS = 2;
    static final int DEFAULT_TURN_SEC = 20, MIN_TURN_SEC = 5, MAX_TURN_SEC = 60;

    static final class P {
        String clientId, nick;
        boolean bot, host, left;
        int seat, pos;          // 0 = 출발 전
        int rolls;              // 굴린 횟수(통계)
        long lastSeen;
    }

    private final String hostClientId;
    /** 0이면 제한시간 없음. 아무도 재촉하지 않는다(봇은 그대로 움직인다). */
    private final int turnSec;
    private Phase phase = Phase.LOBBY;
    private final List<P> players = new ArrayList<>();
    private SnakesBoard board;
    private int turnSeat = -1;
    private int lastDie = 0;
    /** 이번 차례에서 6을 연달아 굴린 횟수. */
    private int extraRolls = 0;
    private LastMove lastMove = null;
    /** 이동 일련번호. 프론트가 '새 이동'을 판별해 연출을 재생하는 기준이다. */
    private long moveSeq = 0;
    private final List<String> log = new ArrayList<>();
    private String lastAction = null;
    private int winnerSeat = -1;
    private String winnerLabel = null;
    private long deadline = 0, botAt = 0, lastActive = System.currentTimeMillis();
    /** 봇이 굴리기까지 기다리는 시간. 테스트에서만 줄인다(한 판이 분 단위가 되지 않게). */
    private long botDelayMs = BOT_DELAY_MS;

    public SnakesGame(String hostClientId, String nick, Integer turnSecOpt) {
        this.hostClientId = hostClientId;
        int ts = turnSecOpt == null ? DEFAULT_TURN_SEC : turnSecOpt;
        this.turnSec = ts <= 0 ? 0 : Math.max(MIN_TURN_SEC, Math.min(MAX_TURN_SEC, ts));
        P host = new P();
        host.clientId = hostClientId; host.nick = clean(nick); host.host = true; host.lastSeen = now();
        players.add(host);
    }

    private void note(String s) { lastAction = s; log.add(s); if (log.size() > 60) log.remove(0); }

    // ── 로비 ──
    public synchronized void join(String clientId, String nick) {
        touch();
        P e = byClient(clientId);
        if (e != null) { e.left = false; e.nick = clean(nick); return; }
        if (phase != Phase.LOBBY) throw bad("이미 시작된 방입니다");
        if (activeCount() >= MAX_PLAYERS) throw bad("정원(10명)이 찼습니다");
        P p = new P(); p.clientId = clientId; p.nick = clean(nick); p.lastSeen = now();
        players.add(p);
    }

    public synchronized void addBot(String clientId) {
        touch(); requireHost(clientId);
        if (phase != Phase.LOBBY) throw bad("이미 시작된 방입니다");
        if (activeCount() >= MAX_PLAYERS) throw bad("정원(10명)이 찼습니다");
        P b = new P(); b.bot = true; b.nick = botName();
        players.add(b);
    }

    public synchronized void start(String clientId) {
        touch(); requireHost(clientId);
        if (phase != Phase.LOBBY) throw bad("이미 시작되었습니다");
        players.removeIf(p -> p.left);
        int n = players.size();
        if (n < 2) throw bad("최소 2명(봇 포함)이 필요합니다");

        board = SnakesBoard.random(n, ThreadLocalRandom.current().nextLong());
        for (int i = 0; i < n; i++) {
            P p = players.get(i);
            p.seat = i; p.pos = 0; p.rolls = 0;
        }
        phase = Phase.PLAYING;
        winnerSeat = -1; winnerLabel = null; lastMove = null; moveSeq = 0; log.clear();
        turnSeat = ThreadLocalRandom.current().nextInt(n);
        note("게임 시작! " + board.cols() + "×" + board.cols() + " · 사다리 "
                + board.ladders().size() + "개 · 뱀 " + board.snakes().size() + "개 (" + n + "인)");
        beginTurn();
    }

    boolean noTimeLimit() { return turnSec <= 0; }

    private void beginTurn() {
        extraRolls = 0;
        deadline = noTimeLimit() ? 0 : now() + turnSec * 1000L;
        botAt = now() + botDelayMs;
    }

    // ── 플레이 ──

    /** 주사위를 굴린다. 6이면 한 번 더 굴릴 차례가 남는다. */
    public synchronized void roll(String clientId) {
        touch();
        requireTurn(clientId);
        doRoll(players.get(turnSeat));
    }

    private void doRoll(P p) {
        int die = 1 + ThreadLocalRandom.current().nextInt(6);
        lastDie = die;
        p.rolls++;

        int from = p.pos;
        int stepped = from + die;
        String how;
        if (stepped > board.size()) {
            stepped = from;                       // 정확히 떨어져야 도착. 넘치면 제자리.
            how = "OVER";
            note(p.nick + " 🎲" + die + " — " + board.size() + "칸을 넘겨서 제자리");
        } else {
            int landed = board.jump(stepped);
            if (landed > stepped) {
                how = "LADDER";
                note(p.nick + " 🎲" + die + " → " + stepped + "칸 🪜 사다리 타고 " + landed + "칸!");
            } else if (landed < stepped) {
                how = "SNAKE";
                note(p.nick + " 🎲" + die + " → " + stepped + "칸 🐍 뱀에게 물려 " + landed + "칸…");
            } else {
                how = "MOVE";
                note(p.nick + " 🎲" + die + " → " + landed + "칸");
            }
            stepped = landed;
        }
        int landedBefore = how.equals("OVER") ? from : from + die;
        lastMove = new LastMove(p.seat, die, from, landedBefore, stepped, how);
        moveSeq++;
        p.pos = stepped;

        if (p.pos >= board.size()) { win(p); return; }

        // 6이면 한 번 더. 다만 연속 상한을 넘기면 차례를 넘긴다.
        if (die == 6 && extraRolls < MAX_EXTRA_ROLLS) {
            extraRolls++;
            note(p.nick + " 6이 나와 한 번 더!");
            deadline = noTimeLimit() ? 0 : now() + turnSec * 1000L;
            botAt = now() + botDelayMs;
            return;
        }
        endTurn();
    }

    private void endTurn() {
        if (phase != Phase.PLAYING) return;
        int n = players.size(), guard = 0;
        do { turnSeat = (turnSeat + 1) % n; guard++; }
        while (guard <= n * 2 && players.get(turnSeat).left);
        beginTurn();
    }

    private void win(P p) {
        phase = Phase.ENDED;
        winnerSeat = p.seat;
        winnerLabel = p.nick;
        deadline = 0;
        note("🏁 " + p.nick + " 도착! 승리 (주사위 " + p.rolls + "번)");
    }

    // ── 봇/타임아웃 ──
    public synchronized void tick() {
        if (phase != Phase.PLAYING || turnSeat < 0) return;
        P cur = players.get(turnSeat);
        if (cur.left) { endTurn(); return; }
        long t = now();
        if (cur.bot) {
            if (t >= botAt) doRoll(cur);
        } else if (!noTimeLimit() && t >= deadline) {
            note(cur.nick + " 시간 초과 — 자동으로 굴립니다");
            doRoll(cur);
        }
    }

    // ── 상태 뷰 ──
    public synchronized SnakesState me(String clientId) {
        touch(); tick();
        P me = byClient(clientId);
        int meSeat = me == null ? -1 : me.seat;

        List<PlayerView> pv = new ArrayList<>();
        for (P p : players) {
            if (p.left && phase == Phase.LOBBY) continue;
            pv.add(new PlayerView(p.seat, p.nick, p.bot, p.host, p == me, p.left, p.pos, p.rolls));
        }
        boolean myTurn = phase == Phase.PLAYING && me != null && meSeat == turnSeat && !me.left;
        String turnName = phase == Phase.PLAYING && turnSeat >= 0 ? players.get(turnSeat).nick : null;

        List<JumpView> ladders = new ArrayList<>(), snakes = new ArrayList<>();
        int cols = 0, size = 0;
        long seed = 0;
        if (board != null) {
            cols = board.cols(); size = board.size(); seed = board.seed();
            for (int[] l : board.ladders()) ladders.add(new JumpView(l[0], l[1]));
            for (int[] s : board.snakes()) snakes.add(new JumpView(s[0], s[1]));
        }

        return new SnakesState(
                phase.name(), cols, size, seed, turnSec, noTimeLimit(),
                clientId != null && clientId.equals(hostClientId), me != null,
                pv, ladders, snakes,
                turnSeat, turnName, nextSeat(), myTurn, meSeat,
                lastDie, lastMove, moveSeq, lastAction, new ArrayList<>(log),
                winnerSeat, winnerLabel, deadline, now());
    }

    /**
     * 좌석 순서상 다음 차례(나간 사람은 건너뜀). 정할 수 없으면 -1.
     *
     * <p>화면의 "다음 차례" 표시가 쓴다. 프론트에서 따로 계산하면 나간 사람 처리가
     * 서버와 어긋난다. 단 6이 나와 한 번 더 굴리는 중이면 차례는 그대로다.
     */
    private int nextSeat() {
        if (phase != Phase.PLAYING || turnSeat < 0) return -1;
        int n = players.size();
        if (n == 0) return -1;
        int s = turnSeat, guard = 0;
        do { s = (s + 1) % n; guard++; }
        while (guard <= n * 2 && players.get(s).left);
        return players.get(s).left ? -1 : s;
    }

    // ── RoomGame ──
    @Override public String roomStatus() {
        return switch (phase) { case LOBBY -> "WAITING"; case ENDED -> "ENDED"; default -> "PLAYING"; };
    }
    @Override public int playerCount() { int n = 0; for (P p : players) if (!p.bot && !p.left) n++; return n; }
    @Override public String hostLabel() { for (P p : players) if (p.host) return p.nick; return "-"; }
    @Override public boolean isEnded() { return phase == Phase.ENDED; }
    @Override public long lastActiveMs() { return lastActive; }
    @Override public synchronized void leave(String clientId) {
        P p = byClient(clientId); if (p == null) return;
        if (phase == Phase.LOBBY) players.remove(p);
        else {
            p.left = true;
            if (phase == Phase.PLAYING) {
                P last = null; int n = 0;
                for (P q : players) if (!q.left) { n++; last = q; }
                if (n == 1 && last != null) win(last);
                else if (p.seat == turnSeat) endTurn();
            }
        }
        touch();
    }

    // ── 유틸/테스트 ──
    public synchronized Phase phase() { return phase; }
    public int turnSeat() { return turnSeat; }
    public int winnerSeat() { return winnerSeat; }
    SnakesBoard board() { return board; }
    void setBotDelayForTest(long ms) { botDelayMs = ms; }
    List<P> playersList() { return players; }
    public P byClient(String clientId) {
        if (clientId == null) return null;
        for (P p : players) if (clientId.equals(p.clientId)) return p;
        return null;
    }

    private void requireTurn(String clientId) {
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        P p = byClient(clientId);
        if (p == null || p.seat != turnSeat) throw bad("당신 차례가 아닙니다");
        if (p.left) throw bad("방을 나간 상태입니다");
    }
    private int activeCount() { int n = 0; for (P p : players) if (!p.left) n++; return n; }
    private void requireHost(String c) { if (!hostClientId.equals(c)) throw bad("방장만 할 수 있습니다"); }
    private String botName() { int n = 1; for (P p : players) if (p.bot) n++; return "봇" + n; }
    private void touch() { lastActive = now(); }
    private static long now() { return System.currentTimeMillis(); }
    private static String clean(String s) {
        String n = s == null ? "" : s.trim();
        if (n.isEmpty()) n = "익명";
        return n.length() > 16 ? n.substring(0, 16) : n;
    }
    private static BusinessException bad(String m) { return new BusinessException(ErrorCode.INVALID_INPUT, m); }
}
