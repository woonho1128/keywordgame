package com.wordplay.halligalli;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.halligalli.dto.HalliGalliStateResponse;
import com.wordplay.halligalli.dto.HalliGalliStateResponse.PlayerView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 할리갈리 한 방(인메모리). 카드 56장(과일 4종 × 1~5개), 2~6인.
 * 차례대로 자기 더미 맨 위 카드를 공개하고, 공개된 같은 과일 합이 정확히 5가 되면 종을 친다.
 * 먼저 친 사람이 모든 공개카드 획득. 잘못 치면 다른 사람들에게 벌칙 카드 지급.
 * 실시간 반응 게임이라 SSE로 즉시 브로드캐스트한다(판정은 서버 권위, synchronized 도착 순서).
 */
public class HalliGalliGame implements RoomGame {

    enum Phase { LOBBY, PLAYING, ENDED }

    static final String[] FRUITS = {"BANANA", "STRAWBERRY", "LIME", "PLUM"};
    record Card(String fruit, int count) {}

    static final class Player {
        final String clientId;
        String nick;
        boolean ai = false;
        String aiLevel = "NORMAL"; // EASY / NORMAL / HARD
        final Deque<Card> down = new ArrayDeque<>();  // 뒤집힌 더미(맨 위 = pollFirst)
        final List<Card> up = new ArrayList<>();       // 공개더미(맨 위 = 마지막)
        Player(String clientId, String nick) { this.clientId = clientId; this.nick = nick; }
        boolean alive() { return !down.isEmpty() || !up.isEmpty(); }
        boolean canFlip() { return !down.isEmpty(); }
        Card top() { return up.isEmpty() ? null : up.get(up.size() - 1); }
        int total() { return down.size() + up.size(); }
    }

    private Phase phase = null;
    private long lastActiveMs = System.currentTimeMillis();
    private String hostClientId = null;
    private final List<Player> players = new ArrayList<>();
    private final Map<String, Integer> seats = new HashMap<>();
    private int currentSeat = 0;
    private int winnerSeat = -1;
    private String lastAction = null;
    private long version = 0;

    // 봇(AI) 타이밍
    private int aiCounter = 0;
    private long turnStartMs = 0;                 // 현재 차례 시작 시각
    private long flipReadyAt = 0;                  // 현재 봇이 카드를 넘길 시각(사람이면 0)
    private long fiveAppearedMs = 0;               // 현재 5가 뜬 시각(없으면 0)
    private final Map<Integer, Long> botRingAt = new HashMap<>(); // 봇 좌석 -> 이번 5에 종 칠 시각
    // 난이도별 [생각시간base, 생각jitter, 반응base, 반응jitter, 놓칠확률%]
    private static final Map<String, int[]> AI_TUNE = Map.of(
            "EASY",   new int[]{1400, 500, 1700, 600, 22},
            "NORMAL", new int[]{1000, 350, 1000, 350, 8},
            "HARD",   new int[]{650,  200, 550,  180, 2}
    );

    // =================== 명령 ===================

    public synchronized HalliGalliStateResponse newGame(String clientId, String nick) {
        reset();
        phase = Phase.LOBBY;
        hostClientId = clientId;
        addPlayer(clientId, nick);
        touch();
        return me(clientId);
    }

    public synchronized HalliGalliStateResponse join(String clientId, String nick) {
        if (phase == null) throw bad("생성된 방이 없습니다");
        if (phase != Phase.LOBBY) throw bad("이미 진행 중이라 참가할 수 없습니다");
        if (!seats.containsKey(clientId)) {
            if (players.size() >= 6) throw bad("정원(6명)이 찼습니다");
            addPlayer(clientId, nick);
        } else {
            players.get(seats.get(clientId)).nick = trimNick(nick);
        }
        touch();
        return me(clientId);
    }

    public synchronized HalliGalliStateResponse start(String clientId) {
        if (phase != Phase.LOBBY) throw bad("지금 시작할 수 없습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 시작할 수 있습니다");
        if (players.size() < 2) throw bad("최소 2명이 필요합니다");

        List<Card> deck = buildDeck();
        Collections.shuffle(deck);
        for (Player p : players) { p.down.clear(); p.up.clear(); }
        int i = 0;
        for (Card c : deck) { players.get(i % players.size()).down.addLast(c); i++; }

        currentSeat = 0;
        winnerSeat = -1;
        phase = Phase.PLAYING;
        lastAction = players.get(0).nick + "님부터 시작합니다.";
        fiveAppearedMs = 0;
        botRingAt.clear();
        setTurnTimers();
        touch();
        return me(clientId);
    }

    /** AI 봇 추가/제거(방장, 대기방). */
    public synchronized HalliGalliStateResponse addAi(String clientId, String level) {
        if (phase != Phase.LOBBY) throw bad("대기방에서만 추가할 수 있습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 추가할 수 있습니다");
        if (players.size() >= 6) throw bad("정원(6명)이 찼습니다");
        String lvl = normalizeLevel(level);
        aiCounter++;
        Player p = new Player("AI#" + aiCounter, "🤖 봇" + aiCounter + "(" + levelLabel(lvl) + ")");
        p.ai = true;
        p.aiLevel = lvl;
        seats.put(p.clientId, players.size());
        players.add(p);
        touch();
        return me(clientId);
    }

    public synchronized HalliGalliStateResponse removeAi(String clientId) {
        if (phase != Phase.LOBBY) throw bad("대기방에서만 가능합니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 가능합니다");
        for (int i = players.size() - 1; i >= 0; i--) {
            if (players.get(i).ai) {
                players.remove(i);
                seats.clear();
                for (int k = 0; k < players.size(); k++) seats.put(players.get(k).clientId, k);
                break;
            }
        }
        touch();
        return me(clientId);
    }

    private static String normalizeLevel(String level) {
        if (level == null) return "NORMAL";
        String u = level.trim().toUpperCase();
        return switch (u) { case "EASY", "NORMAL", "HARD" -> u; default -> "NORMAL"; };
    }

    private static String levelLabel(String lvl) {
        return switch (lvl) { case "EASY" -> "초급"; case "HARD" -> "고급"; default -> "중급"; };
    }

    /** 내 차례에 맨 위 카드 1장 공개. */
    public synchronized HalliGalliStateResponse flip(String clientId) {
        Player me = requirePlayer(clientId);
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        if (seatOf(me) != currentSeat) throw bad("당신의 차례가 아닙니다");
        if (!me.canFlip()) throw bad("뒤집을 카드가 없습니다");
        applyFlip(me);
        return me(clientId);
    }

    private void applyFlip(Player me) {
        me.up.add(me.down.pollFirst());
        lastAction = me.nick + "님이 카드를 공개했습니다.";
        advanceTurn();
        refreshFiveState();
        touch();
    }

    /** 종! 아무나 아무 때나. 공개카드 중 같은 과일 합이 정확히 5면 획득, 아니면 벌칙. */
    public synchronized HalliGalliStateResponse ring(String clientId) {
        Player me = requirePlayer(clientId);
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        if (!me.alive()) throw bad("탈락한 플레이어입니다");
        applyRing(me);
        return me(clientId);
    }

    private void applyRing(Player me) {
        if (hasExactlyFive()) {
            List<Card> pot = new ArrayList<>();
            for (Player p : players) { pot.addAll(p.up); p.up.clear(); }
            Collections.shuffle(pot);
            for (Card c : pot) me.down.addLast(c);
            lastAction = "🔔 " + me.nick + "님이 종을 쳐서 " + pot.size() + "장을 획득!";
            currentSeat = seatOf(me);
            advanceTurn();
        } else {
            int given = 0;
            for (Player p : players) {
                if (p == me || !p.alive()) continue;
                Card c = payOne(me);
                if (c == null) break;
                p.down.addLast(c);
                given++;
            }
            lastAction = "❌ " + me.nick + "님이 잘못 쳐서 " + given + "명에게 벌칙 카드를 냈습니다.";
            if (!me.alive() && currentSeat == seatOf(me)) advanceTurn();
        }
        checkWin();
        refreshFiveState();
        touch();
    }

    public synchronized HalliGalliStateResponse me(String clientId) {
        lastActiveMs = System.currentTimeMillis();
        return build(clientId);
    }

    /** 봇 진행: 사람 폴링마다 호출. 한 번에 한 동작(넘기기 또는 종)만. 상태가 바뀌면 true. */
    public synchronized boolean tick() {
        if (phase != Phase.PLAYING) return false;
        long now = System.currentTimeMillis();
        // 1) 5가 떠 있으면: 반응시간이 지난 봇이 종을 친다(가장 빠른 봇)
        if (hasExactlyFive()) {
            int ringer = -1; long best = Long.MAX_VALUE;
            for (int i = 0; i < players.size(); i++) {
                Player p = players.get(i);
                if (!p.ai || !p.alive()) continue;
                Long d = botRingAt.get(i);
                if (d != null && now >= d && d < best) { best = d; ringer = i; }
            }
            if (ringer >= 0) { lastActiveMs = now; applyRing(players.get(ringer)); return true; }
            return false; // 아직 반응 전 → 사람이 칠 기회
        }
        // 2) 5가 없고 봇 차례면 생각시간 후 한 장 넘긴다
        Player cur = players.get(currentSeat);
        if (cur.ai && cur.canFlip() && flipReadyAt > 0 && now >= flipReadyAt) {
            lastActiveMs = now;
            applyFlip(cur);
            return true;
        }
        return false;
    }

    public synchronized HalliGalliStateResponse resetGame() {
        reset();
        return HalliGalliStateResponse.notStarted(System.currentTimeMillis());
    }

    // =================== 내부 로직 ===================

    /** 다음으로 카드를 낼 수 있는(뒤집을 카드가 있는) 생존자에게 차례를 넘긴다. */
    private void advanceTurn() {
        int n = players.size();
        for (int step = 1; step <= n; step++) {
            int s = (currentSeat + step) % n;
            if (players.get(s).canFlip()) { currentSeat = s; setTurnTimers(); return; }
        }
        // 아무도 뒤집을 수 없음: 5가 떠 있으면 종 대기, 아니면 카드 최다 보유자 승리
        if (!hasExactlyFive()) endByMostCards();
    }

    /** 차례가 넘어갈 때 봇이면 '생각시간' 후 넘기도록 예약. */
    private void setTurnTimers() {
        long now = System.currentTimeMillis();
        turnStartMs = now;
        Player cur = players.get(currentSeat);
        if (cur.ai) {
            int[] t = AI_TUNE.getOrDefault(cur.aiLevel, AI_TUNE.get("NORMAL"));
            flipReadyAt = now + t[0] + ThreadLocalRandom.current().nextInt(t[1] + 1);
        } else {
            flipReadyAt = 0;
        }
    }

    /** 보드에 5가 생겼는지 갱신하고, 새로 생겼으면 봇들의 반응(종 칠) 시각을 정한다. */
    private void refreshFiveState() {
        long now = System.currentTimeMillis();
        if (phase == Phase.PLAYING && hasExactlyFive()) {
            if (fiveAppearedMs == 0) {
                fiveAppearedMs = now;
                botRingAt.clear();
                for (int i = 0; i < players.size(); i++) {
                    Player p = players.get(i);
                    if (!p.ai || !p.alive()) continue;
                    int[] t = AI_TUNE.getOrDefault(p.aiLevel, AI_TUNE.get("NORMAL"));
                    if (ThreadLocalRandom.current().nextInt(100) < t[4]) {
                        botRingAt.put(i, Long.MAX_VALUE); // 이번엔 놓침
                    } else {
                        botRingAt.put(i, now + t[2] + ThreadLocalRandom.current().nextInt(t[3] + 1));
                    }
                }
            }
        } else {
            fiveAppearedMs = 0;
            botRingAt.clear();
        }
    }

    private boolean hasExactlyFive() {
        Map<String, Integer> sum = new HashMap<>();
        for (Player p : players) {
            Card t = p.top();
            if (t != null) sum.merge(t.fruit(), t.count(), Integer::sum);
        }
        return sum.values().stream().anyMatch(v -> v == 5);
    }

    /** 벌칙 카드 1장 지급용: 공개더미 위 → 없으면 뒤집힌 더미 위. */
    private Card payOne(Player p) {
        if (!p.up.isEmpty()) return p.up.remove(p.up.size() - 1);
        if (!p.down.isEmpty()) return p.down.pollFirst();
        return null;
    }

    private void checkWin() {
        int aliveCnt = 0, last = -1;
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).alive()) { aliveCnt++; last = i; }
        }
        if (aliveCnt <= 1) {
            winnerSeat = last;
            phase = Phase.ENDED;
            lastAction = (last >= 0 ? players.get(last).nick : "") + "님 승리!";
        }
    }

    private void endByMostCards() {
        int best = -1, bestSeat = -1;
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).total() > best) { best = players.get(i).total(); bestSeat = i; }
        }
        winnerSeat = bestSeat;
        phase = Phase.ENDED;
        lastAction = "더 낼 카드가 없어 " + (bestSeat >= 0 ? players.get(bestSeat).nick : "") + "님이 최다 카드로 승리!";
    }

    private static List<Card> buildDeck() {
        // 과일별 14장: 1개×5, 2개×3, 3개×3, 4개×2, 5개×1
        int[][] dist = {{1, 5}, {2, 3}, {3, 3}, {4, 2}, {5, 1}};
        List<Card> deck = new ArrayList<>(56);
        for (String f : FRUITS)
            for (int[] d : dist)
                for (int k = 0; k < d[1]; k++) deck.add(new Card(f, d[0]));
        return deck;
    }

    // =================== 응답 ===================

    private HalliGalliStateResponse build(String clientId) {
        long now = System.currentTimeMillis();
        if (phase == null) return HalliGalliStateResponse.notStarted(now);

        Integer mySeat = seats.get(clientId);
        boolean joined = mySeat != null;

        List<PlayerView> pv = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            Card t = p.top();
            pv.add(new PlayerView(i + 1, p.nick, p.down.size(), p.up.size(),
                    t == null ? null : t.fruit(), t == null ? 0 : t.count(), p.alive()));
        }

        return new HalliGalliStateResponse(
                phase.name(),
                now,
                clientId.equals(hostClientId),
                joined,
                joined ? mySeat + 1 : 0,
                joined ? players.get(mySeat).nick : null,
                pv,
                currentSeat + 1,
                joined && phase == Phase.PLAYING && mySeat == currentSeat,
                winnerSeat < 0 ? -1 : winnerSeat + 1,
                winnerSeat < 0 ? null : players.get(winnerSeat).nick,
                lastAction,
                players.size(),
                version
        );
    }

    // =================== RoomGame ===================

    @Override public synchronized String roomStatus() {
        if (phase == null || phase == Phase.LOBBY) return "WAITING";
        return phase == Phase.ENDED ? "ENDED" : "PLAYING";
    }
    @Override public synchronized int playerCount() { return players.size(); }
    @Override public synchronized String hostLabel() { return players.isEmpty() ? "" : players.get(0).nick; }
    @Override public synchronized boolean isEnded() { return phase == Phase.ENDED; }
    @Override public synchronized long lastActiveMs() { return lastActiveMs; }

    List<String> clientIds() { return new ArrayList<>(seats.keySet()); }

    // 테스트용 접근자
    List<Player> playerList() { return players; }
    static List<Card> deckForTest() { return buildDeck(); }

    // =================== 유틸 ===================

    private void touch() { version++; lastActiveMs = System.currentTimeMillis(); }

    private int seatOf(Player p) { return players.indexOf(p); }

    private Player requirePlayer(String clientId) {
        Integer s = seats.get(clientId);
        if (s == null) throw bad("참가하지 않은 기기입니다");
        return players.get(s);
    }

    private void addPlayer(String clientId, String nick) {
        seats.put(clientId, players.size());
        players.add(new Player(clientId, trimNick(nick)));
    }

    private void reset() {
        phase = null; hostClientId = null;
        players.clear(); seats.clear();
        currentSeat = 0; winnerSeat = -1; lastAction = null; version = 0;
        aiCounter = 0; turnStartMs = 0; flipReadyAt = 0; fiveAppearedMs = 0;
        botRingAt.clear();
    }

    private static BusinessException bad(String msg) {
        return new BusinessException(ErrorCode.INVALID_INPUT, msg);
    }

    private static String trimNick(String nick) {
        String t = nick == null ? "" : nick.trim();
        if (t.isEmpty()) t = "익명";
        return t.length() > 16 ? t.substring(0, 16) : t;
    }
}
