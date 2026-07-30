package com.wordplay.sherlock;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.sherlock.dto.SherlockState;
import com.wordplay.sherlock.dto.SherlockState.CharView;
import com.wordplay.sherlock.dto.SherlockState.Clue;
import com.wordplay.sherlock.dto.SherlockState.PlayerView;
import com.wordplay.sherlock.dto.SherlockState.SeatCount;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 셜록13(추리) 멀티플레이 방. 2~10인(+봇).
 *
 * 8개 아이템, 마스터 캐릭터 31종. 매 판 (3×인원+1)종을 골라 공개하고, 1장을 범인으로 숨긴 뒤
 * 나머지를 3장씩 나눠 가진다. 턴마다 ①아이템 전체 조사 ②한 명 조사 ③범인 지목 중 하나.
 * 답변(각자 해당 아이템 카드 개수)은 시스템이 손패에서 자동 계산·공개한다.
 * 범인을 맞히면 승리, 틀리면 탈락. 마지막까지 남으면 승리.
 */
public class SherlockGame implements RoomGame {

    public enum Phase { LOBBY, PLAYING, ENDED }

    static final int MAX_PLAYERS = 10, HAND = 3;
    static final long BOT_DELAY_MS = 1600;
    static final int DEFAULT_TURN_SEC = 60, MIN_TURN_SEC = 15, MAX_TURN_SEC = 180;

    // 8개 아이템(이모지 포함)
    static final String[] ITEMS = {"🔍 돋보기", "🚬 파이프", "👊 주먹", "💀 해골", "🕯️ 등불", "✉️ 편지", "💎 보석", "🔫 권총"};

    record Chr(String name, int[] items) {}
    // 마스터 캐릭터 31종(각 아이템 조합은 유일 → 추리 가능)
    static final Chr[] MASTER = {
            new Chr("에드거 (명탐정)", new int[]{0, 1, 2}), new Chr("루시 (조수)", new int[]{0, 1, 4}),
            new Chr("모리스 (흑막)", new int[]{2, 3, 7}), new Chr("마사 (하숙집 주인)", new int[]{4, 5, 6}),
            new Chr("로버트 (경감)", new int[]{0, 2, 7}), new Chr("아서 (형님)", new int[]{0, 4, 6}),
            new Chr("비비안 (여배우)", new int[]{1, 5, 6}), new Chr("빅터 (저격수)", new int[]{2, 3, 4}),
            new Chr("클라라 (간호사)", new int[]{1, 4, 5}), new Chr("벤저민 (반장)", new int[]{0, 3, 5}),
            new Chr("오스카 (감식관)", new int[]{2, 4, 7}), new Chr("토머스 (순경)", new int[]{3, 6, 7}),
            new Chr("대니 (사환)", new int[]{0, 5, 7}), new Chr("레지날드 (남작)", new int[]{1, 2, 6}),
            new Chr("이자벨라 (백작부인)", new int[]{3, 4, 5}), new Chr("프랜시스 (선장)", new int[]{0, 1, 2, 3}),
            new Chr("헨리 (의사)", new int[]{0, 1, 4, 5}), new Chr("제롬 (교수)", new int[]{2, 3, 6, 7}),
            new Chr("릴리 (도둑)", new int[]{1, 3, 5, 7}), new Chr("소피아 (무용수)", new int[]{0, 2, 4, 6}),
            new Chr("로자 (점술가)", new int[]{1, 2, 4, 7}), new Chr("사이먼 (시계공)", new int[]{0, 3, 4, 7}),
            new Chr("조지 (정원사)", new int[]{1, 3, 4, 6}), new Chr("알프레드 (집사)", new int[]{0, 2, 5, 6}),
            new Chr("잭 (마부)", new int[]{2, 4, 5, 7}), new Chr("노라 (약사)", new int[]{0, 1, 6, 7}),
            new Chr("파블로 (화가)", new int[]{1, 2, 3, 5}), new Chr("에이다 (기자)", new int[]{0, 4, 5, 7}),
            new Chr("미겔 (뱃사공)", new int[]{2, 3, 4, 5}), new Chr("칼 (광부)", new int[]{0, 1, 3, 6}),
            new Chr("마거릿 (재봉사)", new int[]{1, 4, 6, 7}),
    };
    static boolean charHas(int cid, int item) { for (int x : MASTER[cid].items()) if (x == item) return true; return false; }

    static final class P {
        String clientId, nick, botLevel;
        boolean bot, host, left, alive = true;
        int seat;
        final List<Integer> cards = new ArrayList<>();
        int botResolveAt = 999;   // 이 단서 수 이상이면 봇이 지목 시도
        long lastSeen;
    }

    private final String hostClientId;
    private final int turnSec;
    private Phase phase = Phase.LOBBY;
    private final List<P> players = new ArrayList<>();
    private final List<Integer> inPlay = new ArrayList<>(); // 이번 판 캐릭터(마스터 idx), 정렬
    private int culprit = -1;
    private int turnSeat = -1;
    private final List<Clue> clues = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private String lastAction = null;
    private int winnerSeat = -1;
    private String winnerLabel = null;
    private long turnEndsAt = 0, botAt = 0, lastActive = System.currentTimeMillis();

    public SherlockGame(String hostClientId, String nick, Integer turnSecOpt) {
        this.hostClientId = hostClientId;
        int ts = turnSecOpt == null ? DEFAULT_TURN_SEC : turnSecOpt;
        this.turnSec = Math.max(MIN_TURN_SEC, Math.min(MAX_TURN_SEC, ts));
        P host = new P(); host.clientId = hostClientId; host.nick = clean(nick); host.host = true; host.lastSeen = now();
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
    public synchronized void addBot(String clientId, String level) {
        touch(); requireHost(clientId);
        if (phase != Phase.LOBBY) throw bad("이미 시작된 방입니다");
        if (activeCount() >= MAX_PLAYERS) throw bad("정원(10명)이 찼습니다");
        P b = new P(); b.bot = true; b.botLevel = normLevel(level); b.nick = botName();
        players.add(b);
    }
    public synchronized void start(String clientId) {
        touch(); requireHost(clientId);
        if (phase != Phase.LOBBY) throw bad("이미 시작되었습니다");
        int n = activeCount();
        if (n < 2) throw bad("최소 2명(봇 포함)이 필요합니다");
        int deckSize = HAND * n + 1;
        if (deckSize > MASTER.length) throw bad("인원이 너무 많습니다(최대 10명)");

        // 마스터에서 deckSize개 무작위 선택
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < MASTER.length; i++) pool.add(i);
        Collections.shuffle(pool, rng());
        List<Integer> chosen = new ArrayList<>(pool.subList(0, deckSize));
        inPlay.clear(); inPlay.addAll(chosen); Collections.sort(inPlay);

        // 범인 1장 숨기고 나머지 3장씩 배분
        List<Integer> deal = new ArrayList<>(chosen);
        Collections.shuffle(deal, rng());
        culprit = deal.remove(0);
        int idx = 0;
        for (int i = 0; i < players.size(); i++) {
            P p = players.get(i);
            p.seat = i; p.alive = true; p.cards.clear();
            if (p.left) continue;
        }
        // 좌석 순서대로 3장씩
        List<P> seated = new ArrayList<>();
        for (P p : players) if (!p.left) seated.add(p);
        for (P p : seated) for (int k = 0; k < HAND; k++) p.cards.add(deal.get(idx++));

        // 봇 지목 시점(난이도): 단서가 어느 정도 쌓이면 범인 지목
        for (P p : players) if (p.bot) p.botResolveAt = botThreshold(p.botLevel, deckSize);

        phase = Phase.PLAYING;
        winnerSeat = -1; winnerLabel = null; clues.clear(); log.clear();
        turnSeat = 0;
        note("게임 시작! 범인은 " + deckSize + "명 중 숨은 1명. 조사해서 찾아라!");
        beginTurn();
    }

    private int botThreshold(String level, int deckSize) {
        return switch (level) {
            case "EASY" -> deckSize * 2 + ThreadLocalRandom.current().nextInt(deckSize);
            case "HARD" -> Math.max(3, (int) (deckSize * 0.7) + ThreadLocalRandom.current().nextInt(3));
            default -> deckSize + ThreadLocalRandom.current().nextInt(deckSize);
        };
    }
    private void beginTurn() { turnEndsAt = now() + turnSec * 1000L; botAt = now() + BOT_DELAY_MS; }

    // ── 행동 ──
    public synchronized void askAll(String clientId, int item) {
        touch(); tick(); requireTurn(clientId); requireItem(item);
        doAskAll(players.get(turnSeat), item);
    }
    public synchronized void askOne(String clientId, int targetSeat, int item) {
        touch(); tick(); requireTurn(clientId); requireItem(item);
        P p = players.get(turnSeat);
        P q = seat(targetSeat);
        if (q == null || q == p || q.left) throw bad("조사할 상대를 선택하세요");
        doAskOne(p, q, item);
    }
    public synchronized void accuse(String clientId, int charId) {
        touch(); tick(); requireTurn(clientId);
        if (!inPlay.contains(charId)) throw bad("이번 판 캐릭터가 아닙니다");
        doAccuse(players.get(turnSeat), charId);
    }

    private void doAskAll(P p, int item) {
        List<SeatCount> res = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (P q : players) {
            if (q == p || q.left) continue;
            int c = countItem(q, item);
            res.add(new SeatCount(q.seat, c));
            sb.append(q.nick).append(" ").append(c).append("  ");
        }
        clues.add(new Clue(p.seat, p.nick, -1, item, res));
        note(p.nick + " ▸ [전체] " + ITEMS[item] + " → " + sb.toString().trim());
        endTurn();
    }
    private void doAskOne(P p, P q, int item) {
        int c = countItem(q, item);
        clues.add(new Clue(p.seat, p.nick, q.seat, item, List.of(new SeatCount(q.seat, c))));
        note(p.nick + " ▸ [" + q.nick + "] " + ITEMS[item] + " → " + c + "장");
        endTurn();
    }
    private void doAccuse(P p, int charId) {
        if (charId == culprit) {
            phase = Phase.ENDED; winnerSeat = p.seat; winnerLabel = p.nick;
            note("🎯 " + p.nick + " 범인 [" + MASTER[charId].name() + "] 지목 성공! 승리 🏆");
            turnEndsAt = 0;
            return;
        }
        p.alive = false;
        note("❌ " + p.nick + " [" + MASTER[charId].name() + "] 지목 실패 → 탈락");
        int aliveN = 0; P last = null;
        for (P q : players) if (q.alive && !q.left) { aliveN++; last = q; }
        if (aliveN == 1 && last != null) {
            phase = Phase.ENDED; winnerSeat = last.seat; winnerLabel = last.nick;
            note("🏆 " + last.nick + " 최후 생존 · 승리!");
            turnEndsAt = 0; return;
        }
        if (aliveN == 0) { phase = Phase.ENDED; winnerSeat = -1; winnerLabel = "무승부"; turnEndsAt = 0; return; }
        endTurn();
    }

    private int countItem(P q, int item) { int c = 0; for (int cid : q.cards) if (charHas(cid, item)) c++; return c; }

    private void endTurn() {
        if (phase != Phase.PLAYING) return;
        int n = players.size(), guard = 0;
        do { turnSeat = (turnSeat + 1) % n; guard++; }
        while (guard <= n * 2 && (!players.get(turnSeat).alive || players.get(turnSeat).left));
        beginTurn();
    }

    // ── 봇/타임아웃 ──
    public synchronized void tick() {
        if (phase != Phase.PLAYING || turnSeat < 0) return;
        P cur = players.get(turnSeat);
        if (cur.left || !cur.alive) { endTurn(); return; }
        long t = now();
        if (cur.bot) { if (t >= botAt) botMove(cur); }
        else if (t >= turnEndsAt) autoMove(cur);
    }
    private void botMove(P p) {
        botAt = now() + BOT_DELAY_MS;
        if (clues.size() >= p.botResolveAt) {
            // EASY 봇은 가끔 틀린 지목(탈락)
            if ("EASY".equals(p.botLevel) && ThreadLocalRandom.current().nextDouble() < 0.3) {
                int wrong = culprit;
                for (int c : inPlay) if (c != culprit && !heldBySomeone(c)) { wrong = c; break; }
                // 없으면 그냥 범인(운) — 무해
                doAccuse(p, wrong == culprit ? pickWrong(p) : wrong);
            } else doAccuse(p, culprit);
            return;
        }
        // 아직 → 가장 적게 물어본 아이템으로 전체 조사
        int[] cnt = new int[ITEMS.length];
        for (Clue c : clues) cnt[c.item()]++;
        int best = 0; for (int i = 1; i < ITEMS.length; i++) if (cnt[i] < cnt[best]) best = i;
        doAskAll(p, best);
    }
    private int pickWrong(P p) { for (int c : inPlay) if (c != culprit) return c; return culprit; }
    private boolean heldBySomeone(int charId) { for (P q : players) if (q.cards.contains(charId)) return true; return false; }
    private void autoMove(P p) {
        int[] cnt = new int[ITEMS.length];
        for (Clue c : clues) cnt[c.item()]++;
        int best = 0; for (int i = 1; i < ITEMS.length; i++) if (cnt[i] < cnt[best]) best = i;
        doAskAll(p, best);
    }

    // ── 상태 뷰 ──
    public synchronized SherlockState me(String clientId) {
        touch(); tick();
        P me = byClient(clientId);
        int meSeat = me == null ? -1 : me.seat;

        List<PlayerView> pv = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            P p = players.get(i);
            if (p.left && phase == Phase.LOBBY) continue;
            pv.add(new PlayerView(p.seat, p.nick, p.bot, p.host, p == me, p.alive, p.left, p.cards.size()));
        }
        List<CharView> deck = new ArrayList<>();
        if (phase != Phase.LOBBY) for (int cid : inPlay) {
            List<Integer> its = new ArrayList<>();
            for (int x : MASTER[cid].items()) its.add(x);
            deck.add(new CharView(cid, MASTER[cid].name(), its));
        }
        List<Integer> myCards = me != null ? new ArrayList<>(me.cards) : List.of();
        boolean myTurn = phase == Phase.PLAYING && meSeat == turnSeat && me != null && me.alive && !me.left;
        String turnName = phase == Phase.PLAYING && turnSeat >= 0 ? players.get(turnSeat).nick : null;

        return new SherlockState(
                phase.name(),
                turnSec,
                clientId != null && clientId.equals(hostClientId),
                me != null,
                pv, deck, myCards, List.of(ITEMS),
                turnSeat, turnName, myTurn, meSeat,
                new ArrayList<>(clues), lastAction, new ArrayList<>(log),
                winnerSeat, winnerLabel, turnEndsAt, now());
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
        else { p.left = true; p.alive = false; if (phase == Phase.PLAYING && p.seat == turnSeat) endTurn(); }
        touch();
    }

    // ── 유틸 ──
    public synchronized Phase phase() { return phase; }
    public int turnSeat() { return turnSeat; }
    public int winnerSeat() { return winnerSeat; }
    int culpritForTest() { return culprit; }
    List<Integer> inPlayForTest() { return inPlay; }
    List<P> playersList() { return players; }
    public P byClient(String clientId) { if (clientId == null) return null; for (P p : players) if (clientId.equals(p.clientId)) return p; return null; }

    private P seat(int s) { return s >= 0 && s < players.size() ? players.get(s) : null; }
    private void requireTurn(String clientId) {
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        P p = byClient(clientId);
        if (p == null || p.seat != turnSeat) throw bad("당신 차례가 아닙니다");
        if (!p.alive) throw bad("이미 탈락했습니다");
    }
    private void requireItem(int item) { if (item < 0 || item >= ITEMS.length) throw bad("아이템을 선택하세요"); }
    private int activeCount() { int n = 0; for (P p : players) if (!p.left) n++; return n; }
    private void requireHost(String c) { if (!hostClientId.equals(c)) throw bad("방장만 할 수 있습니다"); }
    private String botName() { int n = 1; for (P p : players) if (p.bot) n++; return "봇" + n; }
    private void touch() { lastActive = now(); }
    private static java.util.Random rng() { return ThreadLocalRandom.current(); }
    private static long now() { return System.currentTimeMillis(); }
    private static String clean(String s) { String n = s == null ? "" : s.trim(); if (n.isEmpty()) n = "익명"; return n.length() > 16 ? n.substring(0, 16) : n; }
    private static String normLevel(String s) { String u = s == null ? "NORMAL" : s.toUpperCase(); return switch (u) { case "EASY", "HARD", "NORMAL" -> u; default -> "NORMAL"; }; }
    private static BusinessException bad(String m) { return new BusinessException(ErrorCode.INVALID_INPUT, m); }
}
