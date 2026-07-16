package com.wordplay.mojo;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.mojo.dto.MojoState;
import com.wordplay.mojo.dto.MojoState.FrontCard;
import com.wordplay.mojo.dto.MojoState.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 모죠(Mojo) 멀티플레이 방. 3~8인(+봇). 인메모리·폴링.
 *
 * 카드(숫자=벌점, 색=숫자구간): 🔵0-1×4 🟢2-4×5 🟡5-7×6 🟠8-10×7 🔴11-12×8 (총 78장).
 * 차례: 카드 1장 내고 직전 버림더미 top과 비교 — 낮으면 종료, 높으면 1장 뽑고 종료, 같으면 즉시 한 장 더.
 * 모죠타임: 차례 후 손패 ≤ 임계(2인 2, 그 외 3)면 남은 손패를 앞에 뒷면으로 깔고 매 차례 1장씩 공개.
 * 라운드 종료: 모든 뒷면 공개 완료 또는 손패를 한 번에 0장으로 비움 → 그 사람이 모죠 카드 획득.
 * 점수: 앞면+손패에서 색상별 최고 숫자만 합산. 모죠 보유자는 최저점이면 0, 아니면 +10.
 * 게임 종료: 누적 50점 도달 시, 최저 총점 승.
 */
public class MojoGame implements RoomGame {

    public enum Phase { LOBBY, PLAYING, ENDED }

    static final int MAX_PLAYERS = 8, HAND = 8, TARGET = 50;
    static final long TURN_MS = 60_000, REVEAL_MS = 2500, BOT_DELAY_MS = 1200;

    static final class P {
        String clientId; String nick; boolean bot; String botLevel; boolean host; boolean left;
        final List<Integer> hand = new ArrayList<>();
        final List<Integer> front = new ArrayList<>();
        int frontRevealed = 0;
        boolean inMojo = false;
        int total = 0, roundScore = 0;
        long lastSeen;
    }

    private final boolean doublePile;
    private Phase phase = Phase.LOBBY;
    private final String hostClientId;
    private final List<P> players = new ArrayList<>();
    private final List<List<Integer>> discards = new ArrayList<>();
    private final List<Integer> draw = new ArrayList<>();
    private int turnSeat = -1, roundNum = 0, threshold = 3, mojoHolderSeat = -1, lastActor = -1;
    private boolean mustChain = false;
    private long turnEndsAt = 0, botAt = 0, lastActive = System.currentTimeMillis();
    private String winner = null;
    private String lastAction = null;
    private final java.util.Map<Integer, Integer> lastDrawn = new java.util.HashMap<>(); // seat → 이번에 뽑은 카드

    public MojoGame(String hostClientId, String nick, boolean doublePile) {
        this.hostClientId = hostClientId;
        this.doublePile = doublePile;
        P host = new P(); host.clientId = hostClientId; host.nick = clean(nick); host.host = true; host.lastSeen = now();
        players.add(host);
    }

    // ── 카드/색상/점수 ──────────────────────────────────
    static int colorOf(int n) { return n <= 1 ? 0 : n <= 4 ? 1 : n <= 7 ? 2 : n <= 10 ? 3 : 4; }
    static int countFor(int n) { return n <= 1 ? 4 : n <= 4 ? 5 : n <= 7 ? 6 : n <= 10 ? 7 : 8; }

    /** 카드 묶음의 점수: 색상별 최고 숫자만 합산. */
    static int scoreCards(List<Integer> cards) {
        int[] max = {-1, -1, -1, -1, -1};
        for (int c : cards) { int col = colorOf(c); if (c > max[col]) max[col] = c; }
        int s = 0; for (int m : max) if (m >= 0) s += m;
        return s;
    }

    // ── 로비 ────────────────────────────────────────────
    public synchronized void join(String clientId, String nick) {
        touch();
        P e = byClient(clientId);
        if (e != null) { e.left = false; e.nick = clean(nick); return; }
        if (phase != Phase.LOBBY) throw bad("이미 시작된 방입니다");
        if (activeCount() >= MAX_PLAYERS) throw bad("정원(" + MAX_PLAYERS + "명)이 찼습니다");
        P p = new P(); p.clientId = clientId; p.nick = clean(nick); p.lastSeen = now();
        players.add(p);
    }

    public synchronized void addBot(String clientId, String level) {
        touch(); requireHost(clientId);
        if (phase != Phase.LOBBY) throw bad("이미 시작된 방입니다");
        if (activeCount() >= MAX_PLAYERS) throw bad("정원(" + MAX_PLAYERS + "명)이 찼습니다");
        P b = new P(); b.bot = true; b.botLevel = normLevel(level); b.nick = botName();
        players.add(b);
    }

    public synchronized void start(String clientId) {
        touch(); requireHost(clientId);
        if (phase != Phase.LOBBY) throw bad("이미 시작되었습니다");
        if (activeCount() < 2) throw bad("최소 2명(봇 포함)이 필요합니다");
        for (P p : players) { p.total = 0; p.roundScore = 0; }
        threshold = activeCount() == 2 ? 2 : 3;
        phase = Phase.PLAYING;
        roundNum = 0;
        mojoHolderSeat = firstActive();
        dealRound(mojoHolderSeat);
    }

    // ── 라운드 배분 ─────────────────────────────────────
    private void dealRound(int starter) {
        roundNum++;
        List<Integer> deck = new ArrayList<>();
        for (int n = 0; n <= 12; n++) for (int k = 0; k < countFor(n); k++) deck.add(n);
        Collections.shuffle(deck, new java.util.Random(ThreadLocalRandom.current().nextLong()));
        for (P p : players) {
            p.hand.clear(); p.front.clear(); p.frontRevealed = 0; p.inMojo = false; p.roundScore = 0;
            if (p.left) continue;
            for (int i = 0; i < HAND; i++) p.hand.add(deck.remove(deck.size() - 1));
            p.hand.sort(Integer::compareTo);
        }
        discards.clear();
        int piles = doublePile ? 2 : 1;
        for (int i = 0; i < piles; i++) { List<Integer> d = new ArrayList<>(); d.add(deck.remove(deck.size() - 1)); discards.add(d); }
        draw.clear(); draw.addAll(deck);
        mustChain = false;
        lastDrawn.clear();
        lastAction = "라운드 " + roundNum + " 시작";
        turnSeat = isActive(players.get(starter)) ? starter : nextActiveFrom(starter);
        lastActor = turnSeat;
        setTimers();
    }

    // ── 액션: 카드 내기 ─────────────────────────────────
    public synchronized void play(String clientId, int value, int pile) {
        touch(); tick();
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        P p = byClient(clientId);
        if (p == null || seatOf(p) != turnSeat) throw bad("당신 차례가 아닙니다");
        if (p.inMojo) throw bad("모죠타임에는 카드를 공개만 할 수 있습니다");
        int piles = doublePile ? 2 : 1;
        if (pile < 0 || pile >= piles) throw bad("잘못된 버림더미입니다");
        if (!p.hand.contains(value)) throw bad("가진 카드가 아닙니다");
        doPlay(p, value, pile);
    }

    private void doPlay(P p, int value, int pile) {
        List<Integer> dp = discards.get(pile);
        int prevTop = dp.get(dp.size() - 1);
        p.hand.remove((Integer) value);
        dp.add(value);
        lastActor = turnSeat;
        p.lastSeen = now();
        lastDrawn.remove(turnSeat);
        int cmp = Integer.compare(value, prevTop);
        if (cmp == 0 && !p.hand.isEmpty()) {   // 같음 → 즉시 한 장 더
            mustChain = true;
            lastAction = p.nick + " ▸ " + value + " (같은 숫자! 한 장 더)";
            setTimers();
            return;
        }
        if (cmp > 0) {                          // 높음 → 1장 뽑기
            int drawn = drawOne(p);
            if (drawn >= 0) lastDrawn.put(turnSeat, drawn);
            lastAction = p.nick + " ▸ " + value + " (더미보다 높음 → 🎴 1장 뽑음)";
        } else {                                // 낮음 → 그냥 종료
            lastAction = p.nick + " ▸ " + value + " (더미보다 낮음 → 차례 종료)";
        }
        endTurnChecks(p);
    }

    private int drawOne(P p) {
        if (draw.isEmpty()) refillDraw();
        if (draw.isEmpty()) return -1;
        int c = draw.remove(draw.size() - 1);
        p.hand.add(c); p.hand.sort(Integer::compareTo);
        return c;
    }

    private void refillDraw() {
        for (List<Integer> d : discards) {
            while (d.size() > 1) draw.add(d.remove(0));
        }
        Collections.shuffle(draw, new java.util.Random(ThreadLocalRandom.current().nextLong()));
    }

    private void endTurnChecks(P p) {
        mustChain = false;
        if (p.hand.isEmpty()) { finishRound(seatOf(p)); return; }   // 손패 다 비움 → 라운드 종료
        if (p.hand.size() <= threshold && !p.inMojo) enterMojo(p);
        advanceTurn();
    }

    private void enterMojo(P p) {
        p.front.clear(); p.front.addAll(p.hand); p.hand.clear();
        p.inMojo = true; p.frontRevealed = 0;
    }

    // ── 액션: 모죠타임 공개 ─────────────────────────────
    public synchronized void reveal(String clientId) {
        touch(); tick();
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        P p = byClient(clientId);
        if (p == null || seatOf(p) != turnSeat) throw bad("당신 차례가 아닙니다");
        if (!p.inMojo || p.frontRevealed >= p.front.size()) throw bad("공개할 카드가 없습니다");
        doReveal(turnSeat);
    }

    private void doReveal(int seat) {
        P p = players.get(seat);
        int shown = p.front.get(p.frontRevealed);
        p.frontRevealed++;
        lastActor = seat;
        lastAction = p.nick + " ▸ 모죠 카드 공개 (" + shown + ")";
        p.lastSeen = now();
        advanceTurn();
    }

    // ── 차례 넘김 ───────────────────────────────────────
    private void advanceTurn() {
        int n = players.size();
        for (int step = 1; step <= n; step++) {         // 다른 좌석 우선, 마지막에 현재 좌석
            int s = (turnSeat + step) % n;
            if (isActive(players.get(s))) { turnSeat = s; mustChain = false; setTimers(); return; }
        }
        finishRound(lastActor >= 0 ? lastActor : turnSeat);  // 아무도 행동 못함 → 라운드 종료
    }

    private boolean isActive(P p) {
        if (p.left) return false;
        return p.inMojo ? p.frontRevealed < p.front.size() : !p.hand.isEmpty();
    }
    private int nextActiveFrom(int start) {
        int n = players.size();
        for (int step = 0; step < n; step++) { int s = (start + step) % n; if (isActive(players.get(s))) return s; }
        return start;
    }

    private void setTimers() {
        P cur = players.get(turnSeat);
        botAt = now() + BOT_DELAY_MS;
        turnEndsAt = now() + (cur.inMojo ? REVEAL_MS : TURN_MS);
    }

    // ── 라운드 종료·점수 ────────────────────────────────
    private void finishRound(int enderSeat) {
        mojoHolderSeat = enderSeat;
        List<Integer> raws = new ArrayList<>();
        int min = Integer.MAX_VALUE;
        for (P p : players) {
            if (p.left) { raws.add(-1); continue; }
            List<Integer> all = new ArrayList<>(p.front); all.addAll(p.hand);
            int raw = scoreCards(all);
            raws.add(raw);
            if (raw < min) min = raw;
        }
        for (int i = 0; i < players.size(); i++) {
            P p = players.get(i);
            if (p.left) { p.roundScore = 0; continue; }
            int raw = raws.get(i);
            int add = raw + (i == mojoHolderSeat ? (raw == min ? 0 : 10) : 0);
            p.roundScore = add;
            p.total += add;
        }
        boolean end = false;
        for (P p : players) if (!p.left && p.total >= TARGET) end = true;
        if (end) {
            phase = Phase.ENDED;
            turnEndsAt = 0;
            int best = Integer.MAX_VALUE;
            for (P p : players) if (!p.left) best = Math.min(best, p.total);
            List<String> win = new ArrayList<>();
            for (P p : players) if (!p.left && p.total == best) win.add(p.nick);
            winner = String.join(", ", win);
        } else {
            dealRound(mojoHolderSeat);
        }
    }

    // ── 봇/타임아웃 ─────────────────────────────────────
    public synchronized void tick() {
        if (phase != Phase.PLAYING) return;
        if (turnSeat < 0 || turnSeat >= players.size()) return;
        P cur = players.get(turnSeat);
        if (cur.left) { advanceTurn(); return; }
        long t = now();
        if (cur.inMojo) {
            long due = cur.bot ? botAt : turnEndsAt;
            if (t >= due) doReveal(turnSeat);
        } else {
            if (cur.bot) { if (t >= botAt) autoResolveTurn(cur); }
            else if (t >= turnEndsAt) autoResolveTurn(cur);
        }
    }

    /** 봇/시간초과: 한 차례를 끝까지 자동 처리(같음 연쇄 포함). */
    private void autoResolveTurn(P p) {
        int guard = 0;
        while (seatOf(p) == turnSeat && !p.inMojo && !p.hand.isEmpty() && guard++ < 40) {
            int[] pick = pickPlay(p);
            doPlay(p, pick[0], pick[1]);
            if (!mustChain) break;   // 같음이 아니면 차례 종료됨
        }
    }

    private int[] pickPlay(P p) {
        boolean easy = "EASY".equals(p.botLevel);
        int bestVal = p.hand.get(0), bestPile = 0, bestScore = Integer.MIN_VALUE;
        int piles = doublePile ? 2 : 1;
        for (int pile = 0; pile < piles; pile++) {
            int top = discards.get(pile).get(discards.get(pile).size() - 1);
            for (int v : p.hand) {
                int s;
                if (v < top) s = 1000 + v;        // 안전하게 높은 카드 버리기 선호
                else if (v == top) s = 500 + v;   // 같음(연쇄, 뽑기 없음)
                else s = -(v - top);              // 높음(뽑기) — 초과 적은 쪽
                if (easy) s += ThreadLocalRandom.current().nextInt(0, 300);
                if (s > bestScore) { bestScore = s; bestVal = v; bestPile = pile; }
            }
        }
        return new int[]{bestVal, bestPile};
    }

    // ── 상태 뷰 ─────────────────────────────────────────
    public synchronized MojoState me(String clientId) {
        touch(); tick();
        P me = byClient(clientId);
        int meSeat = me == null ? -1 : seatOf(me);

        List<PlayerView> pv = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            P p = players.get(i);
            if (p.left && phase == Phase.LOBBY) continue;
            boolean owner = (p == me);
            List<FrontCard> fc = new ArrayList<>();
            for (int j = 0; j < p.front.size(); j++) {
                boolean rev = j < p.frontRevealed;
                Integer val = (rev || owner) ? p.front.get(j) : null;  // 비공개 카드는 소유자만 값을 봄
                fc.add(new FrontCard(val, rev));
            }
            pv.add(new PlayerView(i, p.nick, p.bot, p.host, owner, p.hand.size(), fc, p.inMojo, p.frontRevealed,
                    p.total, p.roundScore, p.left, i == mojoHolderSeat && phase != Phase.LOBBY));
        }

        List<Integer> tops = new ArrayList<>(), sizes = new ArrayList<>();
        for (List<Integer> d : discards) { tops.add(d.get(d.size() - 1)); sizes.add(d.size()); }

        boolean myTurn = phase == Phase.PLAYING && meSeat == turnSeat && me != null && !me.left;
        String turnName = phase == Phase.PLAYING && turnSeat >= 0 ? players.get(turnSeat).nick : null;
        List<Integer> myHand = me == null ? List.of() : new ArrayList<>(me.hand);

        return new MojoState(
                phase.name(), doublePile,
                clientId != null && clientId.equals(hostClientId), me != null,
                pv, tops, sizes, draw.size(),
                turnSeat, turnName, myTurn, meSeat,
                me != null && me.inMojo, mustChain, myHand,
                roundNum, phase == Phase.LOBBY ? -1 : mojoHolderSeat,
                lastAction, meSeat >= 0 ? lastDrawn.getOrDefault(meSeat, -1) : -1,
                winner, turnEndsAt, now());
    }

    // ── RoomGame ────────────────────────────────────────
    @Override public String roomStatus() { return switch (phase) { case LOBBY -> "WAITING"; case ENDED -> "ENDED"; default -> "PLAYING"; }; }
    @Override public int playerCount() { int n = 0; for (P p : players) if (!p.bot && !p.left) n++; return n; }
    @Override public String hostLabel() { for (P p : players) if (p.host) return p.nick; return "-"; }
    @Override public boolean isEnded() { return phase == Phase.ENDED; }
    @Override public long lastActiveMs() { return lastActive; }
    @Override public synchronized void leave(String clientId) {
        P p = byClient(clientId); if (p == null) return;
        if (phase == Phase.LOBBY) players.remove(p);
        else { p.left = true; if (phase == Phase.PLAYING && seatOf(p) == turnSeat) advanceTurn(); }
        touch();
    }

    // ── 조회용(테스트/컨트롤러) ─────────────────────────
    public synchronized Phase phase() { return phase; }
    public String winner() { return winner; }
    public int turnSeat() { return turnSeat; }
    public int roundNum() { return roundNum; }
    public List<P> playersList() { return players; }
    public int seatOf(P p) { return players.indexOf(p); }
    public P byClient(String clientId) { if (clientId == null) return null; for (P p : players) if (clientId.equals(p.clientId)) return p; return null; }

    // ── 유틸 ────────────────────────────────────────────
    private int activeCount() { int n = 0; for (P p : players) if (!p.left) n++; return n; }
    private int firstActive() { for (int i = 0; i < players.size(); i++) if (!players.get(i).left) return i; return 0; }
    private void requireHost(String c) { if (!hostClientId.equals(c)) throw bad("방장만 할 수 있습니다"); }
    private String botName() { int n = 1; for (P p : players) if (p.bot) n++; return "봇" + n; }
    private void touch() { lastActive = now(); }
    private static long now() { return System.currentTimeMillis(); }
    private static String clean(String s) { String n = s == null ? "" : s.trim(); if (n.isEmpty()) n = "익명"; return n.length() > 16 ? n.substring(0, 16) : n; }
    private static String normLevel(String s) { String u = s == null ? "NORMAL" : s.toUpperCase(); return switch (u) { case "EASY", "HARD", "NORMAL" -> u; default -> "NORMAL"; }; }
    private static BusinessException bad(String m) { return new BusinessException(ErrorCode.INVALID_INPUT, m); }

    // 테스트 헬퍼
    void speedUpBotsForTest() { botAt = 0; }
    void setThresholdForTest(int t) { threshold = t; }
}
