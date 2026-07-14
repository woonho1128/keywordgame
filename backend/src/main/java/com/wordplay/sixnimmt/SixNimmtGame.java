package com.wordplay.sixnimmt;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.sixnimmt.dto.SixNimmtState;
import com.wordplay.sixnimmt.dto.SixNimmtState.PlayerView;
import com.wordplay.sixnimmt.dto.SixNimmtState.RowCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 젝스님트(6 nimmt!) 게임 방.
 *
 * 흐름: LOBBY → [SELECT(동시 선택) → RESOLVE(낮은 순 배치) → (필요 시 CHOOSE_ROW)] ×10트릭
 *      → 판 종료 → (종료조건 미충족 시) 새 판 → … → ENDED.
 * 6번째 카드를 놓으면 그 줄 5장을 벌점으로 회수. 모든 줄보다 낮으면 줄 하나를 골라 회수.
 * 종료: 66점 도달(POINTS) 또는 N판(HANDS). 최소 벌점 승리.
 */
public class SixNimmtGame implements RoomGame {

    public enum Phase { LOBBY, SELECT, RESOLVE, CHOOSE_ROW, ENDED }
    public enum EndMode { POINTS, HANDS }

    static final int HAND_SIZE = 10, N_ROWS = 4, ROW_MAX = 5, TARGET_POINTS = 66;
    static final long SELECT_MS = 60_000, CHOOSE_MS = 25_000, BOT_DELAY_MS = 1200;

    static final class P {
        String clientId; String nick; boolean bot; String botLevel; boolean host; boolean left;
        final List<Integer> hand = new ArrayList<>();
        int selected = -1;        // 이번 트릭에 낸 카드(엎음). -1 = 미선택
        int penalty = 0;          // 누적 벌점(황소 머리)
        int lastTook = 0;         // 직전 트릭에 회수한 벌점(연출)
        long lastSeen;
    }

    /** 배치 이벤트(연출용): 카드가 어느 줄에 놓였고, 회수했다면 벌점. */
    public record Event(int card, int seat, int row, int took) {}

    private final EndMode endMode;
    private final int targetHands;
    private Phase phase = Phase.LOBBY;
    private final String hostClientId;
    private final List<P> players = new ArrayList<>();
    private final List<List<Integer>> rows = new ArrayList<>();
    private final List<Integer> deck = new ArrayList<>();
    private int handIndex = 0, trickIndex = 0;
    private long deadline = 0, botAt = 0, lastActive = System.currentTimeMillis();
    private String winner = null;

    // RESOLVE 진행 상태
    private List<int[]> resolveQueue;   // [card, seat] 오름차순
    private int resolvePtr = 0;
    private int chooserSeat = -1, chooserCard = -1;
    private final List<Event> trickEvents = new ArrayList<>();

    public SixNimmtGame(String hostClientId, String nick, String endMode, Integer targetHands) {
        this.hostClientId = hostClientId;
        this.endMode = "HANDS".equalsIgnoreCase(endMode) ? EndMode.HANDS : EndMode.POINTS;
        this.targetHands = targetHands == null || targetHands < 1 ? 4 : Math.min(20, targetHands);
        P host = new P(); host.clientId = hostClientId; host.nick = clean(nick); host.host = true; host.lastSeen = now();
        players.add(host);
    }

    // ── 벌점(황소 머리) ─────────────────────────────────
    static int bulls(int card) {
        if (card == 55) return 7;
        if (card % 11 == 0) return 5;
        if (card % 10 == 0) return 3;
        if (card % 5 == 0) return 2;
        return 1;
    }
    static int sumBulls(List<Integer> row) { int s = 0; for (int c : row) s += bulls(c); return s; }

    /** 카드가 들어갈 줄: 끝 카드가 card보다 작으면서 가장 큰 줄. 없으면 -1(모든 줄보다 작음). */
    private int targetRow(int card) {
        int best = -1, bestEnd = -1;
        for (int i = 0; i < rows.size(); i++) {
            int end = rows.get(i).get(rows.get(i).size() - 1);
            if (end < card && end > bestEnd) { bestEnd = end; best = i; }
        }
        return best;
    }

    // ── 로비 ────────────────────────────────────────────
    public synchronized void join(String clientId, String nick) {
        touch();
        P e = byClient(clientId);
        if (e != null) { e.left = false; return; }
        if (phase != Phase.LOBBY) throw bad("이미 시작된 방입니다");
        if (activeCount() >= 10) throw bad("정원(10명)이 찼습니다");
        P p = new P(); p.clientId = clientId; p.nick = clean(nick); p.lastSeen = now();
        players.add(p);
    }

    public synchronized void addBot(String clientId, String level) {
        touch(); requireHost(clientId);
        if (phase != Phase.LOBBY) throw bad("이미 시작된 방입니다");
        if (activeCount() >= 10) throw bad("정원(10명)이 찼습니다");
        P b = new P(); b.bot = true; b.botLevel = normLevel(level); b.nick = botName();
        players.add(b);
    }

    public synchronized void start(String clientId) {
        touch(); requireHost(clientId);
        if (phase != Phase.LOBBY) throw bad("이미 시작되었습니다");
        if (activeCount() < 2) throw bad("최소 2명이 필요합니다");
        for (P p : players) p.penalty = 0;
        handIndex = 0;
        dealHand();
    }

    // ── 판 배분 ─────────────────────────────────────────
    private void dealHand() {
        deck.clear();
        for (int i = 1; i <= 104; i++) deck.add(i);
        Collections.shuffle(deck, new java.util.Random(ThreadLocalRandom.current().nextLong()));
        for (P p : players) { p.hand.clear(); p.selected = -1; p.lastTook = 0; if (p.left) continue; for (int i = 0; i < HAND_SIZE; i++) p.hand.add(deck.remove(deck.size() - 1)); }
        for (P p : players) p.hand.sort(Comparator.naturalOrder());
        rows.clear();
        for (int i = 0; i < N_ROWS; i++) { List<Integer> r = new ArrayList<>(); r.add(deck.remove(deck.size() - 1)); rows.add(r); }
        trickIndex = 0;
        beginSelect();
    }

    private void beginSelect() {
        phase = Phase.SELECT;
        for (P p : players) p.selected = -1;
        trickEvents.clear();
        deadline = now() + SELECT_MS;
        botAt = now() + BOT_DELAY_MS;
    }

    // ── 카드 내기(사람) ─────────────────────────────────
    public synchronized void play(String clientId, int card) {
        touch(); tick();
        if (phase != Phase.SELECT) throw bad("지금은 낼 수 없습니다");
        P p = byClient(clientId);
        if (p == null) throw bad("참가자가 아닙니다");
        if (p.selected != -1) throw bad("이미 카드를 냈습니다");
        if (!p.hand.contains(card)) throw bad("가진 카드가 아닙니다");
        p.selected = card; p.hand.remove((Integer) card); p.lastSeen = now();
        maybeResolve();
    }

    // ── 줄 선택(사람) ───────────────────────────────────
    public synchronized void takeRow(String clientId, int row) {
        touch(); tick();
        if (phase != Phase.CHOOSE_ROW) throw bad("지금은 줄을 고를 수 없습니다");
        P p = byClient(clientId);
        int seat = seatOf(p);
        if (seat != chooserSeat) throw bad("당신 차례가 아닙니다");
        if (row < 0 || row >= rows.size()) throw bad("잘못된 줄입니다");
        doTakeRow(seat, chooserCard, row);
        chooserSeat = -1; chooserCard = -1;
        phase = Phase.RESOLVE;
        resolvePtr++;
        continueResolve();
    }

    // ── 전원 선택되면 RESOLVE 시작 ──────────────────────
    private void maybeResolve() {
        for (P p : players) if (!p.bot && !p.left && p.selected == -1) return;
        for (P p : players) if (p.bot && !p.left && p.selected == -1) botSelect(p); // 봇 즉시 채움
        beginResolve();
    }

    private void beginResolve() {
        resolveQueue = new ArrayList<>();
        for (int s = 0; s < players.size(); s++) { P p = players.get(s); if (p.left || p.selected == -1) continue; resolveQueue.add(new int[]{p.selected, s}); }
        resolveQueue.sort(Comparator.comparingInt(a -> a[0]));
        resolvePtr = 0;
        phase = Phase.RESOLVE;
        continueResolve();
    }

    private void continueResolve() {
        while (resolvePtr < resolveQueue.size()) {
            int[] cs = resolveQueue.get(resolvePtr);
            int card = cs[0], seat = cs[1];
            int row = targetRow(card);
            if (row < 0) {                       // 모든 줄보다 작음 → 줄 선택 필요
                chooserSeat = seat; chooserCard = card;
                phase = Phase.CHOOSE_ROW;
                deadline = now() + CHOOSE_MS;
                botAt = now() + BOT_DELAY_MS;
                if (players.get(seat).bot) { /* 봇은 tick에서 처리 */ }
                return;
            }
            placeCard(card, seat, row);
            resolvePtr++;
        }
        finishTrick();
    }

    private void placeCard(int card, int seat, int row) {
        List<Integer> r = rows.get(row);
        int took = 0;
        if (r.size() >= ROW_MAX) { took = sumBulls(r); players.get(seat).penalty += took; players.get(seat).lastTook += took; r.clear(); }
        r.add(card);
        trickEvents.add(new Event(card, seat, row, took));
    }

    private void doTakeRow(int seat, int card, int row) {
        List<Integer> r = rows.get(row);
        int took = sumBulls(r);
        players.get(seat).penalty += took; players.get(seat).lastTook += took;
        r.clear(); r.add(card);
        trickEvents.add(new Event(card, seat, row, took));
    }

    private void finishTrick() {
        for (P p : players) p.selected = -1;
        trickIndex++;
        boolean handOver = true;
        for (P p : players) if (!p.left && !p.hand.isEmpty()) { handOver = false; break; }
        if (!handOver) { beginSelect(); return; }
        endHandOrGame();
    }

    private void endHandOrGame() {
        boolean end;
        if (endMode == EndMode.POINTS) { int mx = 0; for (P p : players) if (!p.left) mx = Math.max(mx, p.penalty); end = mx >= TARGET_POINTS; }
        else end = (handIndex + 1) >= targetHands;
        if (end) {
            phase = Phase.ENDED;
            P best = null; for (P p : players) if (!p.left && (best == null || p.penalty < best.penalty)) best = p;
            winner = best == null ? null : best.nick;
        } else { handIndex++; dealHand(); }
    }

    // ── 봇 ──────────────────────────────────────────────
    private void botSelect(P b) {
        if (b.hand.isEmpty()) return;
        double bestRisk = Double.MAX_VALUE; int pick = b.hand.get(0);
        boolean easy = "EASY".equals(b.botLevel), hard = "HARD".equals(b.botLevel);
        for (int card : b.hand) {
            double risk;
            int row = targetRow(card);
            if (row < 0) risk = minRowPenalty() + 4;               // 줄 회수 강제
            else {
                List<Integer> r = rows.get(row);
                if (r.size() >= ROW_MAX) risk = sumBulls(r) + 8;    // 내가 6번째 → 회수
                else risk = (card - r.get(r.size() - 1)) * 0.15 + (r.size() == 4 ? 3 : 0);
            }
            if (easy) risk += ThreadLocalRandom.current().nextDouble(0, 6);
            if (hard && row >= 0 && rows.get(row).size() < 4) risk -= 0.5; // 안전한 자리 선호 강화
            if (risk < bestRisk) { bestRisk = risk; pick = card; }
        }
        b.selected = pick; b.hand.remove((Integer) pick);
    }

    private int botChooseRow() {
        int best = 0, bestP = Integer.MAX_VALUE;
        for (int i = 0; i < rows.size(); i++) { int s = sumBulls(rows.get(i)); if (s < bestP) { bestP = s; best = i; } }
        return best;
    }

    private int minRowPenalty() { int m = Integer.MAX_VALUE; for (List<Integer> r : rows) m = Math.min(m, sumBulls(r)); return m; }

    // ── tick(봇·타이머) ─────────────────────────────────
    public synchronized void tick() {
        long t = now();
        if (phase == Phase.SELECT) {
            if (t >= botAt) for (P p : players) if (p.bot && !p.left && p.selected == -1) botSelect(p);
            if (t >= deadline) for (P p : players) if (!p.bot && !p.left && p.selected == -1 && !p.hand.isEmpty()) { int c = p.hand.get(0); p.selected = c; p.hand.remove((Integer) c); }
            boolean allIn = true; for (P p : players) if (!p.left && p.selected == -1 && !(p.hand.isEmpty())) allIn = false;
            if (allIn) { boolean any = false; for (P p : players) if (!p.left && p.selected != -1) any = true; if (any) beginResolve(); }
        } else if (phase == Phase.CHOOSE_ROW) {
            P chooser = players.get(chooserSeat);
            boolean go = false; int row = -1;
            if (chooser.bot && t >= botAt) { row = botChooseRow(); go = true; }
            else if (t >= deadline) { row = botChooseRow(); go = true; }
            if (go) { doTakeRow(chooserSeat, chooserCard, row); chooserSeat = -1; chooserCard = -1; phase = Phase.RESOLVE; resolvePtr++; continueResolve(); }
        }
    }

    // ── 상태 뷰(클라이언트별) ───────────────────────────
    public synchronized SixNimmtState me(String clientId) {
        touch(); tick();
        P me = byClient(clientId);
        int meSeat = me == null ? -1 : seatOf(me);

        List<List<RowCard>> rowsView = new ArrayList<>();
        for (List<Integer> r : rows) { List<RowCard> rc = new ArrayList<>(); for (int c : r) rc.add(new RowCard(c, bulls(c))); rowsView.add(rc); }

        List<PlayerView> pv = new ArrayList<>();
        for (P p : players) {
            if (p.left && phase == Phase.LOBBY) continue;
            pv.add(new PlayerView(p.nick, p.bot, p.botLevel, p.host, p == me, p.penalty, p.selected != -1, p.lastTook, p.left));
        }

        List<RowCard> myHand = new ArrayList<>();
        if (me != null) for (int c : me.hand) myHand.add(new RowCard(c, bulls(c)));

        boolean myTurn = phase == Phase.SELECT && me != null && me.selected == -1 && !me.hand.isEmpty();
        boolean iAmChooser = phase == Phase.CHOOSE_ROW && meSeat == chooserSeat;
        String chooserName = phase == Phase.CHOOSE_ROW && chooserSeat >= 0 ? players.get(chooserSeat).nick : null;

        return new SixNimmtState(
                phase.name(), endMode.name(), targetHands, handIndex,
                clientId != null && clientId.equals(hostClientId), me != null,
                rowsView, pv, myHand, myTurn, iAmChooser, chooserName,
                new ArrayList<>(trickEvents), winner, deadline, now());
    }

    // ── 조회용 게터 ─────────────────────────────────────
    public synchronized Phase phase() { return phase; }
    public EndMode endMode() { return endMode; }
    public int targetHands() { return targetHands; }
    public int handIndex() { return handIndex; }
    public String winner() { return winner; }
    public long deadline() { return deadline; }
    public int chooserSeat() { return chooserSeat; }
    public List<List<Integer>> rows() { return rows; }
    public List<Event> trickEvents() { return trickEvents; }
    public List<P> playersList() { return players; }
    public String hostClientId() { return hostClientId; }
    public int seatOf(P p) { return players.indexOf(p); }
    public P byClient(String clientId) { if (clientId == null) return null; for (P p : players) if (clientId.equals(p.clientId)) return p; return null; }

    // ── RoomGame ────────────────────────────────────────
    @Override public String roomStatus() { return switch (phase) { case LOBBY -> "WAITING"; case ENDED -> "ENDED"; default -> "PLAYING"; }; }
    @Override public int playerCount() { int n = 0; for (P p : players) if (!p.bot && !p.left) n++; return n; }
    @Override public String hostLabel() { for (P p : players) if (p.host) return p.nick; return "-"; }
    @Override public boolean isEnded() { return phase == Phase.ENDED; }
    @Override public long lastActiveMs() { return lastActive; }
    @Override public synchronized void leave(String clientId) {
        P p = byClient(clientId); if (p == null) return;
        if (phase == Phase.LOBBY) players.remove(p); else p.left = true;
        touch();
    }

    // ── 유틸 ────────────────────────────────────────────
    private int activeCount() { int n = 0; for (P p : players) if (!p.left) n++; return n; }
    private void requireHost(String c) { if (!hostClientId.equals(c)) throw bad("방장만 할 수 있습니다"); }
    private String botName() { int n = 1; for (P p : players) if (p.bot) n++; return "봇" + n; }
    private void touch() { lastActive = now(); }
    private static long now() { return System.currentTimeMillis(); }
    private static String clean(String s) { String n = s == null ? "" : s.trim(); if (n.isEmpty()) n = "익명"; return n.length() > 16 ? n.substring(0, 16) : n; }
    private static String normLevel(String s) { String u = s == null ? "NORMAL" : s.toUpperCase(); return switch (u) { case "EASY", "HARD", "NORMAL" -> u; default -> "NORMAL"; }; }
    private static BusinessException bad(String m) { return new BusinessException(ErrorCode.INVALID_INPUT, m); }

    // 테스트 헬퍼
    void forceDealForTest() { for (P p : players) p.penalty = 0; handIndex = 0; dealHand(); }
    List<P> playersForTest() { return players; }
    void speedUpBotsForTest() { botAt = 0; } // 봇 지연 제거(테스트에서 실시간 대기 회피)
}
