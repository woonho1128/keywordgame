package com.wordplay.lexio;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.lexio.dto.LexioStateResponse;
import com.wordplay.lexio.dto.LexioStateResponse.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 렉시오(Lexio) — 빅투 계열 타일 게임. 인메모리·폴링 진행.
 *
 * 타일: id 0..59, suit=id/15(0구름<1별<2달<3해), number=id%15+1(1~15).
 * 숫자 세기(약→강): 3,4,…,15,1,2. → numRank: 3→0 … 15→12, 1→13, 2→14.
 * 족보(약→강): 싱글 · 원페어 · 트리플 · [스트레이트<플러시<풀하우스<포카드<스트레이트플러시].
 */
public class LexioGame implements RoomGame {

    enum Phase { LOBBY, PLAYING, ROUND_END, ENDED }
    // 5장 조합 세기(정렬용). 싱글/페어/트리플은 같은 장수끼리만 비교하므로 0.
    enum HandType { SINGLE, PAIR, TRIPLE, STRAIGHT, FLUSH, FULLHOUSE, FOURPLUSONE, STRAIGHTFLUSH }

    private static final long DEFAULT_TURN_MS = 40_000L;

    static final class Player {
        final String clientId;
        String nick;
        boolean ai = false;
        String botName = null;
        final List<Integer> hand = new ArrayList<>();
        int score = 0;          // 누적 벌점(낮을수록 좋음)
        boolean out = false;    // 이번 판 손패 소진
        Player(String clientId, String nick) { this.clientId = clientId; this.nick = nick; }
    }

    // ---- 상태 ----
    private Phase phase = null;
    private long lastActiveMs = System.currentTimeMillis();
    private String hostClientId = null;
    private final List<Player> players = new ArrayList<>();
    private final Map<String, Integer> seats = new HashMap<>();
    private final Set<String> leftClients = new HashSet<>();
    private int botCounter = 0;

    private String theme = "BLACK";       // BLACK / WHITE
    private String scoreMode = "SINGLE";  // SINGLE / ACCUMULATE
    private long turnMs = DEFAULT_TURN_MS;

    private int currentSeat = 0;
    private List<Integer> tableHand = new ArrayList<>(); // 현재 테이블에 놓인 마지막 조합(비어있으면 새 선)
    private int tableSeat = -1;                            // 그 조합을 낸 좌석
    private final Set<Integer> passed = new HashSet<>();   // 이번 트릭 패스한 좌석
    private boolean firstLead = false;                     // 이번 판 첫 선(최저타일 포함 강제)
    private int lowestTileInPlay = -1;
    private long turnDeadlineMs = 0;
    private long botActAt = 0;
    private String lastAction = null;
    private int roundWinnerSeat = -1;
    private int gameWinnerSeat = -1;
    private long version = 0;

    // =================== 명령 ===================

    public synchronized LexioStateResponse newGame(String clientId, String nick, String theme, String scoreMode, Integer turnSec) {
        reset();
        phase = Phase.LOBBY;
        hostClientId = clientId;
        this.theme = "WHITE".equalsIgnoreCase(theme) ? "WHITE" : "BLACK";
        this.scoreMode = "ACCUMULATE".equalsIgnoreCase(scoreMode) ? "ACCUMULATE" : "SINGLE";
        this.turnMs = (turnSec == null ? 40 : Math.max(20, Math.min(180, turnSec))) * 1000L;
        addPlayer(clientId, nick);
        touch();
        return me(clientId);
    }

    public synchronized LexioStateResponse join(String clientId, String nick) {
        if (phase == null) throw bad("생성된 방이 없습니다");
        if (phase != Phase.LOBBY) throw bad("이미 진행 중이라 참가할 수 없습니다");
        if (!seats.containsKey(clientId)) {
            if (players.size() >= 5) throw bad("정원(5명)이 찼습니다");
            addPlayer(clientId, nick);
        } else {
            players.get(seats.get(clientId)).nick = trimNick(nick);
        }
        touch();
        return me(clientId);
    }

    public synchronized LexioStateResponse addBot(String clientId) {
        if (phase != Phase.LOBBY) throw bad("대기방에서만 봇을 추가할 수 있습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 추가할 수 있습니다");
        if (players.size() >= 5) throw bad("정원(5명)이 찼습니다");
        botCounter++;
        Player b = new Player("bot::" + botCounter + "::" + System.nanoTime(), "🤖 봇" + botCounter);
        b.ai = true; b.botName = b.nick;
        players.add(b);
        seats.put(b.clientId, players.size() - 1);
        touch();
        return me(clientId);
    }

    public synchronized LexioStateResponse start(String clientId) {
        if (phase != Phase.LOBBY) throw bad("지금 시작할 수 없습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 시작할 수 있습니다");
        if (players.size() < 2) throw bad("최소 2명이 필요합니다");
        for (Player p : players) p.score = 0;
        gameWinnerSeat = -1;
        deal();
        phase = Phase.PLAYING;
        touch();
        return me(clientId);
    }

    /** 조합 내기. tiles=낼 타일 id들. */
    public synchronized LexioStateResponse play(String clientId, List<Integer> tiles) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        if (seatOf(me) != currentSeat) throw bad("당신의 차례가 아닙니다");
        if (tiles == null || tiles.isEmpty()) throw bad("낼 타일을 고르세요");
        Set<Integer> uniq = new HashSet<>(tiles);
        if (uniq.size() != tiles.size()) throw bad("타일이 중복되었습니다");
        if (!me.hand.containsAll(tiles)) throw bad("내 손패가 아닙니다");
        Hand h = evaluate(tiles);
        if (h == null) throw bad("유효하지 않은 조합입니다");
        // 받아치기 규칙
        if (!tableHand.isEmpty()) {
            if (tiles.size() != tableHand.size()) throw bad("같은 장수로만 받아칠 수 있습니다 (" + tableHand.size() + "장)");
            if (h.strength() <= evaluate(tableHand).strength()) throw bad("더 높은 조합만 낼 수 있습니다");
        }
        // 첫 선은 최저 타일 포함 필수
        if (firstLead && !tiles.contains(lowestTileInPlay))
            throw bad("첫 턴에는 가장 낮은 타일을 포함해서 내야 합니다");

        applyPlay(me, tiles, h);
        return me(clientId);
    }

    public synchronized LexioStateResponse pass(String clientId) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        if (seatOf(me) != currentSeat) throw bad("당신의 차례가 아닙니다");
        if (tableHand.isEmpty()) throw bad("새 선은 패스할 수 없습니다. 내야 합니다");
        applyPass(me);
        return me(clientId);
    }

    /** 누적 모드: 다음 판 시작. */
    public synchronized LexioStateResponse nextRound(String clientId) {
        tick();
        if (!clientId.equals(hostClientId)) throw bad("방장만 다음 판을 시작할 수 있습니다");
        if (phase != Phase.ROUND_END) throw bad("지금은 다음 판을 시작할 수 없습니다");
        deal();
        phase = Phase.PLAYING;
        touch();
        return me(clientId);
    }

    public synchronized LexioStateResponse resetGame() {
        reset();
        return LexioStateResponse.notStarted(System.currentTimeMillis());
    }

    public synchronized LexioStateResponse me(String clientId) {
        lastActiveMs = System.currentTimeMillis();
        tick();
        return build(clientId);
    }

    // =================== 진행 ===================

    private void deal() {
        List<Integer> bag = new ArrayList<>();
        for (int i = 0; i < 60; i++) bag.add(i);
        Collections.shuffle(bag);
        int per = switch (players.size()) { case 2 -> 20; case 3 -> 20; case 4 -> 15; default -> 12; };
        int idx = 0;
        for (Player p : players) {
            p.hand.clear();
            p.out = false;
            for (int k = 0; k < per; k++) p.hand.add(bag.get(idx++));
            p.hand.sort(LexioGame::tileCompare);
        }
        // 이번 판에 쓰인 타일 중 세기가 가장 낮은 타일 소지자가 선
        int lowSeat = 0, lowest = -1;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < players.size(); i++) for (int id : players.get(i).hand) {
            if (tileKey(id) < best) { best = tileKey(id); lowSeat = i; lowest = id; }
        }
        lowestTileInPlay = lowest;
        currentSeat = lowSeat;
        tableHand = new ArrayList<>();
        tableSeat = -1;
        passed.clear();
        firstLead = true;
        roundWinnerSeat = -1;
        lastAction = players.get(currentSeat).nick + "님부터 시작합니다.";
        turnDeadlineMs = System.currentTimeMillis() + turnMs;
        botActAt = System.currentTimeMillis() + botDelay();
        touch();
    }

    private void applyPlay(Player me, List<Integer> tiles, Hand h) {
        me.hand.removeAll(tiles);
        tableHand = new ArrayList<>(tiles);
        tableHand.sort(LexioGame::tileCompare);
        tableSeat = seatOf(me);
        passed.clear();
        firstLead = false;
        lastAction = me.nick + "님이 " + handLabel(h) + " 냄";
        if (me.hand.isEmpty()) { me.out = true; onSomeoneOut(seatOf(me)); return; }
        advanceTurn();
        touch();
    }

    private void applyPass(Player me) {
        passed.add(seatOf(me));
        lastAction = me.nick + "님 패스";
        // 살아있는(아직 out 아닌) 나머지가 전부 패스했으면 테이블 낸 사람이 새 선
        if (everyoneElsePassed()) {
            tableHand = new ArrayList<>();
            passed.clear();
            currentSeat = tableSeat >= 0 ? tableSeat : nextAliveSeat(currentSeat);
            // tableSeat 플레이어가 이미 out이면 다음 생존자에게
            if (players.get(currentSeat).out) currentSeat = nextAliveSeat(currentSeat);
            lastAction += " · " + players.get(currentSeat).nick + "님 새 선";
            turnDeadlineMs = System.currentTimeMillis() + turnMs;
            botActAt = System.currentTimeMillis() + botDelay();
            touch();
            return;
        }
        advanceTurn();
        touch();
    }

    private void onSomeoneOut(int seat) {
        roundWinnerSeat = seat;
        if ("SINGLE".equals(scoreMode)) {
            gameWinnerSeat = seat;
            phase = Phase.ENDED;
            lastAction = players.get(seat).nick + "님 승리!";
            turnDeadlineMs = 0;
            touch();
            return;
        }
        // 누적: 벌점 계산 후 라운드 종료
        for (Player p : players) {
            if (seatOf(p) == seat) continue;
            int remain = p.hand.size();
            int mult = remain >= 10 ? 2 : 1;
            p.score += remain * mult;
        }
        phase = Phase.ROUND_END;
        lastAction = players.get(seat).nick + "님이 이번 판 승리! (방장이 다음 판 시작)";
        turnDeadlineMs = 0;
        touch();
    }

    private void advanceTurn() {
        currentSeat = nextAliveSeat(currentSeat);
        turnDeadlineMs = System.currentTimeMillis() + turnMs;
        botActAt = System.currentTimeMillis() + botDelay();
    }

    /** 다음으로 행동할 좌석(out·이번트릭 패스 제외). 트릭 흐름상 패스한 사람은 이번 트릭 스킵. */
    private int nextAliveSeat(int from) {
        int n = players.size();
        for (int step = 1; step <= n; step++) {
            int s = (from + step) % n;
            if (!players.get(s).out && !passed.contains(s)) return s;
        }
        return from;
    }

    private boolean everyoneElsePassed() {
        int active = 0;
        for (int i = 0; i < players.size(); i++)
            if (!players.get(i).out && !passed.contains(i)) active++;
        return active <= 1; // 테이블 낸 사람 1명만 남음
    }

    // ---- 타이머/봇 ----
    private void tick() {
        if (phase != Phase.PLAYING) return;
        long now = System.currentTimeMillis();
        // 턴 타임아웃
        if (turnDeadlineMs > 0 && now >= turnDeadlineMs) {
            Player cur = players.get(currentSeat);
            if (tableHand.isEmpty()) autoLead(cur); else applyPass(cur);
            return;
        }
        // 봇 행동(한 번에 하나)
        Player cur = players.get(currentSeat);
        if (cur.ai && now >= botActAt) botAct(cur);
    }

    private void botAct(Player bot) {
        List<Integer> playIds;
        if (tableHand.isEmpty()) {
            playIds = botLead(bot);
        } else {
            playIds = botBeat(bot);
        }
        if (playIds == null) { applyPass(bot); return; }
        Hand h = evaluate(playIds);
        applyPlay(bot, playIds, h);
    }

    private void autoLead(Player p) {
        List<Integer> ids = botLead(p);
        if (ids == null) ids = List.of(p.hand.get(0));
        applyPlay(p, ids, evaluate(ids));
    }

    /** 봇/자동 선: 첫 선이면 최저타일 싱글, 아니면 가장 약한 싱글. */
    private List<Integer> botLead(Player bot) {
        if (bot.hand.isEmpty()) return null;
        if (firstLead && bot.hand.contains(lowestTileInPlay)) return List.of(lowestTileInPlay);
        return List.of(bot.hand.get(0)); // hand는 tileCompare 오름차순
    }

    /** 봇 받아치기: 같은 장수로 이기는 최소 조합. 없으면 null(패스). */
    private List<Integer> botBeat(Player bot) {
        int size = tableHand.size();
        long target = evaluate(tableHand).strength();
        List<Integer> best = null; long bestStr = Long.MAX_VALUE;
        List<List<Integer>> combos = combinations(bot.hand, size);
        for (List<Integer> c : combos) {
            Hand h = evaluate(c);
            if (h == null) continue;
            long s = h.strength();
            if (s > target && s < bestStr) { bestStr = s; best = c; }
        }
        return best;
    }

    // =================== 족보 판별/비교 ===================

    record Hand(HandType type, long key) {
        long strength() { return (long) type.ordinal() * 1_000_000L + key; }
    }

    /** 유효한 조합이면 Hand, 아니면 null. */
    static Hand evaluate(List<Integer> ids) {
        int n = ids.size();
        if (n == 1) return new Hand(HandType.SINGLE, tileKey(ids.get(0)));
        if (n == 2) {
            if (num(ids.get(0)) != num(ids.get(1))) return null;
            return new Hand(HandType.PAIR, (long) numRank(num(ids.get(0))) * 4 + maxSuit(ids));
        }
        if (n == 3) {
            if (!sameNumber(ids)) return null;
            return new Hand(HandType.TRIPLE, numRank(num(ids.get(0))));
        }
        if (n != 5) return null;
        List<Integer> s = new ArrayList<>(ids);
        s.sort(LexioGame::tileCompareByNaturalNumber);
        boolean flush = s.stream().map(LexioGame::suit).distinct().count() == 1;
        boolean straight = isNaturalStraight(s);
        Map<Integer, Integer> cnt = new HashMap<>();
        for (int id : s) cnt.merge(num(id), 1, Integer::sum);
        int quad = -1, triple = -1, pair = -1;
        for (var e : cnt.entrySet()) {
            if (e.getValue() == 4) quad = e.getKey();
            else if (e.getValue() == 3) triple = e.getKey();
            else if (e.getValue() == 2) pair = e.getKey();
        }
        if (straight && flush) return new Hand(HandType.STRAIGHTFLUSH, straightKey(s));
        if (quad >= 0) return new Hand(HandType.FOURPLUSONE, numRank(quad));
        if (triple >= 0 && pair >= 0) return new Hand(HandType.FULLHOUSE, numRank(triple));
        if (flush) return new Hand(HandType.FLUSH, highestTileKey(s));
        if (straight) return new Hand(HandType.STRAIGHT, straightKey(s));
        return null;
    }

    private static boolean sameNumber(List<Integer> ids) {
        int a = num(ids.get(0));
        for (int id : ids) if (num(id) != a) return false;
        return true;
    }
    private static boolean isNaturalStraight(List<Integer> sortedByNat) {
        for (int i = 1; i < sortedByNat.size(); i++)
            if (num(sortedByNat.get(i)) != num(sortedByNat.get(i - 1)) + 1) return false;
        return true;
    }
    /** 스트레이트 세기: 자연수 최고 타일의 세기(numRank·suit). */
    private static long straightKey(List<Integer> sortedByNat) {
        int top = sortedByNat.get(sortedByNat.size() - 1);
        return tileKey(top);
    }
    private static long highestTileKey(List<Integer> ids) {
        long best = -1;
        for (int id : ids) best = Math.max(best, tileKey(id));
        return best;
    }
    private static int maxSuit(List<Integer> ids) {
        int m = 0; for (int id : ids) m = Math.max(m, suit(id)); return m;
    }

    // =================== 타일 유틸 ===================

    static int suit(int id) { return id / 15; }
    static int num(int id) { return id % 15 + 1; }
    static int numRank(int n) { return n >= 3 ? n - 3 : (n == 1 ? 13 : 14); }
    /** 단일 타일 세기: numRank*4 + suit. */
    static long tileKey(int id) { return (long) numRank(num(id)) * 4 + suit(id); }
    static int tileCompare(int a, int b) { return Long.compare(tileKey(a), tileKey(b)); }
    static int tileCompareByNaturalNumber(int a, int b) {
        if (num(a) != num(b)) return Integer.compare(num(a), num(b));
        return Integer.compare(suit(a), suit(b));
    }

    private static List<List<Integer>> combinations(List<Integer> src, int k) {
        List<List<Integer>> out = new ArrayList<>();
        combo(src, k, 0, new ArrayList<>(), out);
        return out;
    }
    private static void combo(List<Integer> src, int k, int start, List<Integer> cur, List<List<Integer>> out) {
        if (cur.size() == k) { out.add(new ArrayList<>(cur)); return; }
        for (int i = start; i < src.size(); i++) {
            cur.add(src.get(i));
            combo(src, k, i + 1, cur, out);
            cur.remove(cur.size() - 1);
        }
    }

    private static String handLabel(Hand h) {
        return switch (h.type()) {
            case SINGLE -> "싱글"; case PAIR -> "원페어"; case TRIPLE -> "트리플";
            case STRAIGHT -> "스트레이트"; case FLUSH -> "플러시"; case FULLHOUSE -> "풀하우스";
            case FOURPLUSONE -> "포카드"; case STRAIGHTFLUSH -> "스트레이트 플러시";
        };
    }

    // =================== 응답 ===================

    private LexioStateResponse build(String clientId) {
        long now = System.currentTimeMillis();
        if (phase == null) return LexioStateResponse.notStarted(now);
        Integer mySeat = seats.get(clientId);
        boolean joined = mySeat != null;

        List<PlayerView> pv = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            pv.add(new PlayerView(i + 1, p.nick, p.ai, p.hand.size(), p.score, p.out, passed.contains(i)));
        }
        List<Integer> myTiles = joined ? new ArrayList<>(players.get(mySeat).hand) : List.of();
        boolean myTurn = joined && phase == Phase.PLAYING && mySeat == currentSeat;

        return new LexioStateResponse(
                phase.name(), now, theme, scoreMode,
                clientId.equals(hostClientId), joined,
                joined ? mySeat + 1 : 0, joined ? players.get(mySeat).nick : null,
                pv, myTiles,
                phase == Phase.PLAYING ? currentSeat + 1 : -1, myTurn,
                new ArrayList<>(tableHand), tableSeat < 0 ? -1 : tableSeat + 1,
                tableHand.isEmpty() ? null : handLabel(evaluate(tableHand)),
                phase == Phase.PLAYING ? turnDeadlineMs : 0,
                joined && phase == Phase.PLAYING && mySeat == currentSeat && firstLead ? lowestTileInPlay : -1,
                lastAction,
                roundWinnerSeat < 0 ? -1 : roundWinnerSeat + 1,
                gameWinnerSeat < 0 ? -1 : gameWinnerSeat + 1,
                players.size(), version);
    }

    // =================== RoomGame ===================

    @Override public synchronized String roomStatus() {
        if (phase == null || phase == Phase.LOBBY) return "WAITING";
        return phase == Phase.ENDED ? "ENDED" : "PLAYING";
    }
    @Override public synchronized int playerCount() {
        return (int) players.stream().filter(p -> !p.ai && !leftClients.contains(p.clientId)).count();
    }
    @Override public synchronized String hostLabel() { return players.isEmpty() ? "" : players.get(0).nick; }
    @Override public synchronized boolean isEnded() { return phase == Phase.ENDED; }
    @Override public synchronized long lastActiveMs() { return lastActiveMs; }
    @Override public synchronized void leave(String clientId) {
        Integer seat = seats.get(clientId);
        if (seat == null) return;
        lastActiveMs = System.currentTimeMillis();
        if (phase == null || phase == Phase.LOBBY) {
            players.remove((int) seat);
            seats.clear();
            for (int i = 0; i < players.size(); i++) seats.put(players.get(i).clientId, i);
            if (clientId.equals(hostClientId)) hostClientId = players.isEmpty() ? null : players.get(0).clientId;
        } else {
            leftClients.add(clientId);
        }
    }

    // 테스트용
    static Hand evalForTest(List<Integer> ids) { return evaluate(ids); }

    // =================== 유틸 ===================

    private long botDelay() { return 900 + ThreadLocalRandom.current().nextInt(900); }
    private void touch() { version++; lastActiveMs = System.currentTimeMillis(); }

    private void addPlayer(String clientId, String nick) {
        seats.put(clientId, players.size());
        players.add(new Player(clientId, trimNick(nick)));
    }
    private int seatOf(Player p) { return players.indexOf(p); }
    private Player requirePlayer(String clientId) {
        Integer s = seats.get(clientId);
        if (s == null) throw bad("참가하지 않은 기기입니다");
        return players.get(s);
    }

    private void reset() {
        phase = null; hostClientId = null;
        players.clear(); seats.clear(); leftClients.clear();
        botCounter = 0;
        theme = "BLACK"; scoreMode = "SINGLE"; turnMs = DEFAULT_TURN_MS;
        currentSeat = 0; tableHand = new ArrayList<>(); tableSeat = -1;
        passed.clear(); firstLead = false; lowestTileInPlay = -1;
        turnDeadlineMs = 0; botActAt = 0; lastAction = null;
        roundWinnerSeat = gameWinnerSeat = -1; version = 0;
    }

    private static BusinessException bad(String msg) { return new BusinessException(ErrorCode.INVALID_INPUT, msg); }
    private static String trimNick(String nick) {
        String t = nick == null ? "" : nick.trim();
        if (t.isEmpty()) t = "익명";
        return t.length() > 16 ? t.substring(0, 16) : t;
    }
}
