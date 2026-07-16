package com.wordplay.yacht;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.yacht.dto.YachtState;
import com.wordplay.yacht.dto.YachtState.PlayerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 야찌(Yahtzee) 멀티플레이 방. 2~8인 + 봇. 인메모리·폴링.
 *
 * 흐름: LOBBY → PLAYING(턴제: 주사위 5개를 최대 3번 굴려 족보 1칸 확정) → ENDED.
 * 모든 참가자가 13칸을 채우면 종료, 총점(상단 63↑ 보너스 +35 포함) 최고 승리.
 */
public class YachtGame implements RoomGame {

    public enum Phase { LOBBY, PLAYING, ENDED }

    /** 족보. key는 프론트 표기와 일치. */
    public enum Cat {
        ONES("ones"), TWOS("twos"), THREES("threes"), FOURS("fours"), FIVES("fives"), SIXES("sixes"),
        THREE_KIND("threeKind"), FOUR_KIND("fourKind"), FULL_HOUSE("fullHouse"),
        SMALL_STRAIGHT("smallStraight"), LARGE_STRAIGHT("largeStraight"), YAHTZEE("yahtzee"), CHANCE("chance");
        final String key;
        Cat(String k) { this.key = k; }
        static Cat of(String key) { for (Cat c : values()) if (c.key.equals(key)) return c; return null; }
    }

    static final int MAX_PLAYERS = 8, DICE = 5, ROLLS = 3, N_CATS = 13, UPPER_BONUS_AT = 63, UPPER_BONUS = 35;
    static final long TURN_MS = 90_000, BOT_DELAY_MS = 1500;

    static final class P {
        String clientId; String nick; boolean bot; String botLevel; boolean host; boolean left;
        final Map<String, Integer> card = new LinkedHashMap<>();
        long lastSeen;
    }

    private Phase phase = Phase.LOBBY;
    private final String hostClientId;
    private final List<P> players = new ArrayList<>();
    private int turnSeat = -1;
    private final int[] dice = new int[DICE];
    private final boolean[] held = new boolean[DICE];
    private int rollsLeft = 0;
    private boolean rolled = false;
    private long deadline = 0, botAt = 0, lastActive = System.currentTimeMillis();
    private String winner = null;

    public YachtGame(String hostClientId, String nick) {
        this.hostClientId = hostClientId;
        P host = new P(); host.clientId = hostClientId; host.nick = clean(nick); host.host = true; host.lastSeen = now();
        players.add(host);
    }

    // ── 점수 계산 ───────────────────────────────────────
    static int[] counts(int[] d) { int[] c = new int[7]; for (int v : d) c[v]++; return c; }
    static boolean hasRun(int[] c, int len) {
        int run = 0;
        for (int v = 1; v <= 6; v++) { run = c[v] > 0 ? run + 1 : 0; if (run >= len) return true; }
        return false;
    }
    static int scoreOf(Cat cat, int[] d) {
        int[] c = counts(d); int sum = 0; for (int v : d) sum += v;
        return switch (cat) {
            case ONES -> c[1] * 1;
            case TWOS -> c[2] * 2;
            case THREES -> c[3] * 3;
            case FOURS -> c[4] * 4;
            case FIVES -> c[5] * 5;
            case SIXES -> c[6] * 6;
            case THREE_KIND -> anyGe(c, 3) ? sum : 0;
            case FOUR_KIND -> anyGe(c, 4) ? sum : 0;
            case FULL_HOUSE -> (has(c, 3) && has(c, 2)) ? 25 : 0;
            case SMALL_STRAIGHT -> hasRun(c, 4) ? 30 : 0;
            case LARGE_STRAIGHT -> hasRun(c, 5) ? 40 : 0;
            case YAHTZEE -> has(c, 5) ? 50 : 0;
            case CHANCE -> sum;
        };
    }
    private static boolean anyGe(int[] c, int n) { for (int i = 1; i <= 6; i++) if (c[i] >= n) return true; return false; }
    private static boolean has(int[] c, int n) { for (int i = 1; i <= 6; i++) if (c[i] == n) return true; return false; }

    static int upperSum(Map<String, Integer> card) {
        int s = 0;
        for (Cat c : new Cat[]{Cat.ONES, Cat.TWOS, Cat.THREES, Cat.FOURS, Cat.FIVES, Cat.SIXES})
            s += card.getOrDefault(c.key, 0);
        return s;
    }
    static int total(Map<String, Integer> card) {
        int up = upperSum(card);
        int sum = 0; for (int v : card.values()) sum += v;
        return sum + (up >= UPPER_BONUS_AT ? UPPER_BONUS : 0);
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
        for (P p : players) p.card.clear();
        winner = null;
        phase = Phase.PLAYING;
        turnSeat = firstActive();
        startTurn();
    }

    // ── 턴 진행 ─────────────────────────────────────────
    private void startTurn() {
        for (int i = 0; i < DICE; i++) { dice[i] = die(); held[i] = false; }
        rollsLeft = ROLLS - 1;   // 첫 굴림 자동
        rolled = true;
        deadline = now() + TURN_MS;
        botAt = now() + BOT_DELAY_MS;
    }

    public synchronized void roll(String clientId) {
        touch(); tick();
        requireMyTurn(clientId);
        if (rollsLeft <= 0) throw bad("더 굴릴 수 없습니다");
        for (int i = 0; i < DICE; i++) if (!held[i]) dice[i] = die();
        rollsLeft--; rolled = true;
        players.get(turnSeat).lastSeen = now();
    }

    public synchronized void toggleHold(String clientId, int index) {
        touch(); tick();
        requireMyTurn(clientId);
        if (!rolled) throw bad("먼저 굴려야 합니다");
        if (index < 0 || index >= DICE) throw bad("잘못된 주사위입니다");
        held[index] = !held[index];
        players.get(turnSeat).lastSeen = now();
    }

    public synchronized void pick(String clientId, String catKey) {
        touch(); tick();
        requireMyTurn(clientId);
        Cat cat = Cat.of(catKey);
        if (cat == null) throw bad("잘못된 족보입니다");
        P p = players.get(turnSeat);
        if (p.card.containsKey(cat.key)) throw bad("이미 채운 칸입니다");
        applyPick(p, cat);
    }

    private void applyPick(P p, Cat cat) {
        p.card.put(cat.key, scoreOf(cat, dice));
        p.lastSeen = now();
        advance();
    }

    private void advance() {
        int n = players.size();
        for (int step = 1; step <= n; step++) {
            int cand = (turnSeat + step) % n;
            P p = players.get(cand);
            if (!p.left && p.card.size() < N_CATS) { turnSeat = cand; startTurn(); return; }
        }
        endGame();
    }

    private void endGame() {
        phase = Phase.ENDED;
        deadline = 0;
        P best = null; int bestT = Integer.MIN_VALUE;
        for (P p : players) {
            if (p.left) continue;
            int t = total(p.card);
            if (t > bestT) { bestT = t; best = p; }
        }
        winner = best == null ? null : best.nick;
    }

    // ── 봇/타임아웃 자동 처리 ───────────────────────────
    public synchronized void tick() {
        if (phase != Phase.PLAYING) return;
        long t = now();
        if (turnSeat < 0 || turnSeat >= players.size()) return;
        P cur = players.get(turnSeat);
        if (cur.left) { advance(); return; }
        if (cur.bot) {
            if (t >= botAt) botTakeTurn(cur);
            return;
        }
        if (deadline > 0 && t >= deadline) autoPick(cur); // 사람 시간초과 → 최선 자동 확정
    }

    private void botTakeTurn(P b) {
        // 남은 두 번은 가장 많은 값을 유지하고 나머지 재굴림(간단 그리디)
        for (int r = 0; r < ROLLS - 1; r++) {
            int keep = mostCommon(dice);
            for (int i = 0; i < DICE; i++) held[i] = dice[i] == keep;
            for (int i = 0; i < DICE; i++) if (!held[i]) dice[i] = die();
        }
        rollsLeft = 0; rolled = true;
        applyPick(b, chooseCategory(b));
    }

    private void autoPick(P p) { applyPick(p, chooseCategory(p)); }

    /** 현재 주사위로 남은 족보 중 최고 점수를 고르되, 0점뿐이면 낮은 가치 칸을 희생. */
    private Cat chooseCategory(P p) {
        Cat best = null; int bestPts = -1;
        for (Cat c : Cat.values()) {
            if (p.card.containsKey(c.key)) continue;
            int pts = scoreOf(c, dice);
            if (pts > bestPts) { bestPts = pts; best = c; }
        }
        if (bestPts <= 0) {
            // 버릴 칸 우선순위(낮은 가치부터)
            Cat[] sacrifice = {Cat.ONES, Cat.TWOS, Cat.THREES, Cat.FOURS, Cat.FIVES, Cat.SIXES,
                    Cat.FULL_HOUSE, Cat.SMALL_STRAIGHT, Cat.LARGE_STRAIGHT, Cat.YAHTZEE, Cat.FOUR_KIND, Cat.THREE_KIND, Cat.CHANCE};
            for (Cat c : sacrifice) if (!p.card.containsKey(c.key)) return c;
        }
        return best;
    }

    private static int mostCommon(int[] d) {
        int[] c = counts(d); int best = 1;
        for (int v = 1; v <= 6; v++) if (c[v] >= c[best]) best = v; // 동수면 큰 값
        return best;
    }

    // ── 상태 뷰 ─────────────────────────────────────────
    public synchronized YachtState me(String clientId) {
        touch(); tick();
        P me = byClient(clientId);
        int meSeat = me == null ? -1 : seatOf(me);

        List<PlayerView> pv = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            P p = players.get(i);
            if (p.left && phase == Phase.LOBBY) continue;
            pv.add(new PlayerView(i, p.nick, p.bot, p.botLevel, p.host, p == me,
                    new LinkedHashMap<>(p.card), upperSum(p.card), total(p.card), p.left));
        }

        boolean myTurn = phase == Phase.PLAYING && meSeat == turnSeat && me != null && !me.left;
        String turnName = phase == Phase.PLAYING && turnSeat >= 0 ? players.get(turnSeat).nick : null;

        List<Integer> diceView = new ArrayList<>();
        List<Boolean> heldView = new ArrayList<>();
        if (phase == Phase.PLAYING) for (int i = 0; i < DICE; i++) { diceView.add(dice[i]); heldView.add(held[i]); }

        return new YachtState(
                phase.name(),
                clientId != null && clientId.equals(hostClientId),
                me != null,
                pv, turnSeat, turnName, myTurn, meSeat,
                diceView, heldView, rollsLeft, rolled,
                winner, deadline, now());
    }

    // ── RoomGame ────────────────────────────────────────
    @Override public String roomStatus() { return switch (phase) { case LOBBY -> "WAITING"; case ENDED -> "ENDED"; default -> "PLAYING"; }; }
    @Override public int playerCount() { int n = 0; for (P p : players) if (!p.bot && !p.left) n++; return n; }
    @Override public String hostLabel() { for (P p : players) if (p.host) return p.nick; return "-"; }
    @Override public boolean isEnded() { return phase == Phase.ENDED; }
    @Override public long lastActiveMs() { return lastActive; }
    @Override public synchronized void leave(String clientId) {
        P p = byClient(clientId); if (p == null) return;
        if (phase == Phase.LOBBY) { players.remove(p); }
        else {
            p.left = true;
            if (phase == Phase.PLAYING && seatOf(p) == turnSeat) advance(); // 진행 중 차례였던 사람이 나감 → 넘김
        }
        touch();
    }

    // ── 조회용 게터(테스트/컨트롤러) ────────────────────
    public synchronized Phase phase() { return phase; }
    public String winner() { return winner; }
    public int turnSeat() { return turnSeat; }
    public List<P> playersList() { return players; }
    public int seatOf(P p) { return players.indexOf(p); }
    public P byClient(String clientId) { if (clientId == null) return null; for (P p : players) if (clientId.equals(p.clientId)) return p; return null; }

    // ── 유틸 ────────────────────────────────────────────
    private void requireMyTurn(String clientId) {
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        P p = byClient(clientId);
        if (p == null || seatOf(p) != turnSeat) throw bad("당신 차례가 아닙니다");
    }
    private int activeCount() { int n = 0; for (P p : players) if (!p.left) n++; return n; }
    private int firstActive() { for (int i = 0; i < players.size(); i++) if (!players.get(i).left) return i; return 0; }
    private void requireHost(String c) { if (!hostClientId.equals(c)) throw bad("방장만 할 수 있습니다"); }
    private String botName() { int n = 1; for (P p : players) if (p.bot) n++; return "봇" + n; }
    private void touch() { lastActive = now(); }
    private static int die() { return 1 + ThreadLocalRandom.current().nextInt(6); }
    private static long now() { return System.currentTimeMillis(); }
    private static String clean(String s) { String n = s == null ? "" : s.trim(); if (n.isEmpty()) n = "익명"; return n.length() > 16 ? n.substring(0, 16) : n; }
    private static String normLevel(String s) { String u = s == null ? "NORMAL" : s.toUpperCase(); return switch (u) { case "EASY", "HARD", "NORMAL" -> u; default -> "NORMAL"; }; }
    private static BusinessException bad(String m) { return new BusinessException(ErrorCode.INVALID_INPUT, m); }

    // 테스트 헬퍼
    void speedUpBotsForTest() { botAt = 0; }
    void setDiceForTest(int[] d) { System.arraycopy(d, 0, dice, 0, DICE); rolled = true; rollsLeft = 0; }
}
