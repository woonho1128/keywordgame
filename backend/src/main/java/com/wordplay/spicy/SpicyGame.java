package com.wordplay.spicy;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.spicy.dto.SpicyState;
import com.wordplay.spicy.dto.SpicyState.CardView;
import com.wordplay.spicy.dto.SpicyState.PlayerView;
import com.wordplay.spicy.dto.SpicyState.Reveal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 스파이시(Spicy) 블러핑 카드게임. 2~10인(+봇).
 *
 * 카드를 뒷면으로 내면서 "스파이스 + 숫자"를 선언한다. 첫 장은 1~3, 이후엔 같은 스파이스의 더 높은
 * 숫자여야 하고, 10에 도달하면 다음 사람부터 아무 스파이스나 1~3으로 다시 시작한다. 손패가 안 맞으면
 * 거짓말을 하거나 패스(덱에서 1장)해야 한다.
 *
 * 도전은 '숫자 도전'인지 '스파이스 도전'인지 콕 집어야 하며, 지목한 쪽이 실제와 다르면 도전 성공,
 * 같으면 실패다(거짓말이었어도 엉뚱한 쪽을 지목하면 도전자가 진다). 승자가 더미를 가져가 점수로 쌓고,
 * 패자는 2장을 더 받는다.
 *
 * 손패를 모두 내려놓으면 트로피(10점)를 받고 6장을 새로 받는다. 트로피 2개를 모으거나, 트로피가 모두
 * 소진되거나, 종료 카드가 덱 맨 위에 드러나면 게임이 끝나고 점수를 계산한다.
 *
 * 인원 확장(하우스 룰): 6인까지는 원작 구성(스파이스별 1~10 3세트 + 와일드 10장 = 100장) 그대로 쓰고,
 * 7인 이상은 세트를 통째로 늘려 '인당 카드 수'를 원작과 같게 유지한다.
 */
public class SpicyGame implements RoomGame {

    public enum Phase { LOBBY, PLAYING, ENDED }
    public enum TurnPhase { PLAY, CHALLENGE }

    static final int MAX_PLAYERS = 10, SPICES = 3, MAX_NUMBER = 10, HAND = 6, TROPHY_TO_WIN = 2;
    /** 와일드 표기: ALL = 그 속성은 어떤 선언이든 맞음, NONE = 그 속성은 어떤 선언이든 틀림. */
    static final int ALL = -1, NONE = -2;
    static final long BOT_DELAY_MS = 1800;
    static final int TURN_SEC = 45;
    static final int DEFAULT_CHALLENGE_SEC = 8, MIN_CHALLENGE_SEC = 3, MAX_CHALLENGE_SEC = 30;

    public static final String[] SPICE_NAMES = {"🌶️ 고추", "🥬 와사비", "🧂 후추"};

    /** 인원별 스파이스 세트 수 — 6인까지 원작(3세트), 이후 인당 카드 수를 맞춰 늘린다. */
    static int setsFor(int players) { return players <= 6 ? 3 : players <= 8 ? 4 : 5; }
    /** 와일드는 '모든 숫자'·'모든 스파이스' 각각 이 개수만큼(원작 5+5). */
    static int wildEachFor(int players) { return players <= 6 ? 5 : players <= 8 ? 7 : 8; }
    /** 트로피도 인원에 맞춰 늘린다(2개 모으면 승리는 그대로). */
    static int trophiesFor(int players) { return players <= 6 ? 3 : players <= 8 ? 4 : 5; }

    /**
     * spice/number 가 ALL 이면 그 속성은 항상 맞고, NONE 이면 항상 틀리다.
     * '모든 숫자 카드' = (spice NONE, number ALL) → 스파이스 도전에 항상 진다.
     * '모든 스파이스 카드' = (spice ALL, number NONE) → 숫자 도전에 항상 진다.
     * 종료 카드는 end=true.
     */
    record Card(int id, int spice, int number, boolean end) {
        Card(int id, int spice, int number) { this(id, spice, number, false); }
        boolean spiceMatches(int declared) { return spice == ALL || (spice != NONE && spice == declared); }
        boolean numberMatches(int declared) { return number == ALL || (number != NONE && number == declared); }
    }

    static final class P {
        String clientId, nick, botLevel;
        boolean bot, host, left;
        int seat;
        final List<Card> hand = new ArrayList<>();
        int won;        // 획득한 스파이시 카드 장수(1장 1점)
        int trophies;
        long lastSeen;
    }

    private final String hostClientId;
    /** 0이면 제한시간 없음 — 아무도 재촉당하지 않고, 도전 창은 모두가 '통과'를 눌러야 넘어간다. */
    private final int challengeSec;
    private final boolean handPenalty;

    private Phase phase = Phase.LOBBY;
    private TurnPhase turnPhase = null;
    private final List<P> players = new ArrayList<>();

    private final List<Card> draw = new ArrayList<>();   // 맨 뒤가 맨 위
    private final List<Card> pile = new ArrayList<>();   // 현재 더미(뒷면). 맨 뒤가 맨 위
    private int deckSize, trophyTotal, trophyLeft;

    private int turnSeat = -1, lastPlayerSeat = -1;
    private int declaredSpice = -1, declaredNumber = -1; // 더미 맨 위의 '선언' 값
    private Reveal lastReveal = null;
    private final List<String> log = new ArrayList<>();
    private String lastAction = null;
    private int winnerSeat = -1;
    private String winnerLabel = null;
    private long deadline = 0, botAt = 0, lastActive = System.currentTimeMillis();
    private boolean botChallengeDone = false;
    /** 제한시간 없음 모드에서 이번 도전 창을 '통과'한 좌석들. */
    private final java.util.Set<Integer> passVotes = new java.util.HashSet<>();

    public SpicyGame(String hostClientId, String nick, Integer challengeSecOpt, Boolean handPenaltyOpt) {
        this.hostClientId = hostClientId;
        int cs = challengeSecOpt == null ? DEFAULT_CHALLENGE_SEC : challengeSecOpt;
        // 0은 '제한 없음'이라는 뜻이라 그대로 둔다.
        this.challengeSec = cs <= 0 ? 0 : Math.max(MIN_CHALLENGE_SEC, Math.min(MAX_CHALLENGE_SEC, cs));
        this.handPenalty = handPenaltyOpt == null || handPenaltyOpt;   // 원작 룰이라 기본 켬
        P host = new P(); host.clientId = hostClientId; host.nick = clean(nick); host.host = true; host.lastSeen = now();
        players.add(host);
    }

    private void note(String s) { lastAction = s; log.add(s); if (log.size() > 80) log.remove(0); }

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
        players.removeIf(p -> p.left);
        int n = players.size();
        if (n < 2) throw bad("최소 2명(봇 포함)이 필요합니다");

        buildDeck(n);
        for (int i = 0; i < n; i++) {
            P p = players.get(i);
            p.seat = i; p.hand.clear(); p.won = 0; p.trophies = 0;
            for (int k = 0; k < HAND; k++) dealOne(p);
        }
        trophyTotal = trophiesFor(n); trophyLeft = trophyTotal;
        phase = Phase.PLAYING;
        winnerSeat = -1; winnerLabel = null; log.clear(); lastReveal = null;
        pile.clear(); declaredSpice = -1; declaredNumber = -1; lastPlayerSeat = -1;
        turnSeat = ThreadLocalRandom.current().nextInt(n);
        note("게임 시작! 카드 " + deckSize + "장 · 트로피 " + trophyTotal + "개 (" + n + "인)"
                + (handPenalty ? " · 손패 감점 있음" : ""));
        beginPlay();
    }

    /** 인원에 맞춰 덱을 만든다. 세트를 통째로 늘리므로 1~10 분포는 항상 균일하다. */
    private void buildDeck(int n) {
        draw.clear();
        int sets = setsFor(n), wildEach = wildEachFor(n), id = 0;
        for (int s = 0; s < SPICES; s++)
            for (int set = 0; set < sets; set++)
                for (int v = 1; v <= MAX_NUMBER; v++) draw.add(new Card(id++, s, v));
        for (int i = 0; i < wildEach; i++) draw.add(new Card(id++, NONE, ALL));   // 모든 숫자 카드
        for (int i = 0; i < wildEach; i++) draw.add(new Card(id++, ALL, NONE));   // 모든 스파이스 카드
        deckSize = draw.size();
        Collections.shuffle(draw, ThreadLocalRandom.current());

        // 종료 카드는 덱 아래쪽(하위 20% 구간)에 섞어 넣는다 — 드러나면 그 즉시 게임이 끝난다.
        int lower = Math.max(1, draw.size() / 5);
        draw.add(ThreadLocalRandom.current().nextInt(lower), new Card(id, NONE, NONE, true));
    }

    /** 나눠주기/드로우 1장. 종료 카드가 드러나면 게임을 끝낸다. */
    private void dealOne(P p) {
        if (draw.isEmpty()) { endByScore("덱이 모두 소진되어"); return; }
        Card c = draw.remove(draw.size() - 1);
        if (c.end()) { endByScore("종료 카드가 나와"); return; }
        p.hand.add(c);
    }

    /** 제한시간 없음 모드인가. */
    boolean noTimeLimit() { return challengeSec <= 0; }

    private void beginPlay() {
        turnPhase = TurnPhase.PLAY;
        deadline = noTimeLimit() ? 0 : now() + TURN_SEC * 1000L;
        botAt = now() + BOT_DELAY_MS;
        botChallengeDone = false;
        passVotes.clear();
    }

    // ── 선언 가능한 값 ──
    /** 새 더미거나 10에 도달했으면 1~3, 아니면 직전보다 큰 수. */
    List<Integer> playableNumbers() {
        List<Integer> out = new ArrayList<>();
        if (declaredNumber < 0 || declaredNumber >= MAX_NUMBER) { out.add(1); out.add(2); out.add(3); return out; }
        for (int v = declaredNumber + 1; v <= MAX_NUMBER; v++) out.add(v);
        return out;
    }
    /**
     * 진행 중에는 직전과 같은 스파이스만.
     * 새 더미이거나 10에 도달한 뒤에는 같은 스파이스든 다른 스파이스든 아무거나 고를 수 있다.
     */
    List<Integer> playableSpices() {
        List<Integer> out = new ArrayList<>();
        if (declaredSpice < 0 || declaredNumber >= MAX_NUMBER) { for (int s = 0; s < SPICES; s++) out.add(s); return out; }
        out.add(declaredSpice);
        return out;
    }

    // ── 행동 ──
    /** 손패의 cardId를 내면서 spice/number를 선언한다(실제 카드와 달라도 된다 = 블러핑). */
    public synchronized void play(String clientId, int cardId, int spice, int number) {
        touch(); tick();
        requireTurn(clientId);
        if (turnPhase != TurnPhase.PLAY) throw bad("지금은 낼 수 없습니다");
        P p = players.get(turnSeat);
        Card c = null;
        for (Card x : p.hand) if (x.id() == cardId) { c = x; break; }
        if (c == null) throw bad("가지고 있지 않은 카드입니다");
        if (!playableNumbers().contains(number)) throw bad("선언할 수 없는 숫자입니다");
        if (!playableSpices().contains(spice)) throw bad("선언할 수 없는 스파이스입니다");
        doPlay(p, c, spice, number);
    }

    /** 낼 카드가 없을 때(또는 내기 싫을 때) 패스하고 덱에서 1장 가져간다. */
    public synchronized void pass(String clientId) {
        touch(); tick();
        requireTurn(clientId);
        if (turnPhase != TurnPhase.PLAY) throw bad("지금은 패스할 수 없습니다");
        doPass(players.get(turnSeat));
    }

    private void doPass(P p) {
        dealOne(p);
        note("🙅 " + p.nick + " 패스 · 카드 1장 가져감");
        if (phase != Phase.PLAYING) return;
        nextTurn();
        beginPlay();
    }

    private void doPlay(P p, Card c, int spice, int number) {
        p.hand.remove(c);
        pile.add(c);
        declaredSpice = spice; declaredNumber = number;
        lastPlayerSeat = p.seat;
        lastReveal = null;
        note("🃏 " + p.nick + " ▸ " + SPICE_NAMES[spice] + " " + number + " 선언 (더미 " + pile.size() + "장)");
        turnPhase = TurnPhase.CHALLENGE;
        deadline = noTimeLimit() ? 0 : now() + challengeSec * 1000L;
        botAt = now() + BOT_DELAY_MS;
        botChallengeDone = false;
        passVotes.clear();
    }

    /** 직전 카드에 도전. kind: "NUMBER"(숫자 도전) 또는 "SPICE"(스파이스 도전). */
    public synchronized void challenge(String clientId, String kind) {
        touch(); tick();
        if (phase != Phase.PLAYING || turnPhase != TurnPhase.CHALLENGE) throw bad("지금은 도전할 수 없습니다");
        P ch = byClient(clientId);
        if (ch == null || ch.left) throw bad("도전할 수 없습니다");
        if (ch.seat == lastPlayerSeat) throw bad("자기 카드에는 도전할 수 없습니다");
        String k = "SPICE".equalsIgnoreCase(kind) ? "SPICE" : "NUMBER";
        resolveChallenge(ch, k);
    }

    /**
     * 제한시간 없음 모드에서 '도전하지 않겠다'를 알린다.
     * 도전할 수 있는 사람이 모두 통과하면 선언이 그대로 통과된다(타이머 대신 쓰는 진행 장치).
     */
    public synchronized void passChallenge(String clientId) {
        touch(); tick();
        if (phase != Phase.PLAYING || turnPhase != TurnPhase.CHALLENGE) throw bad("지금은 통과할 수 없습니다");
        P p = byClient(clientId);
        if (p == null || p.left) throw bad("통과할 수 없습니다");
        if (p.seat == lastPlayerSeat) throw bad("자기 카드는 통과할 수 없습니다");
        passVotes.add(p.seat);
        if (allPassed()) resolveNoChallenge();
    }

    /** 도전 가능한 사람 수(낸 사람 제외). */
    private int challengerCount() {
        int n = 0;
        for (P p : players) if (!p.left && p.seat != lastPlayerSeat) n++;
        return n;
    }
    private boolean allPassed() {
        for (P p : players) if (!p.left && p.seat != lastPlayerSeat && !passVotes.contains(p.seat)) return false;
        return true;
    }

    private void resolveChallenge(P challenger, String kind) {
        P accused = players.get(lastPlayerSeat);
        Card top = pile.get(pile.size() - 1);

        // 지목한 쪽이 실제와 '다르면' 도전 성공. 와일드는 ALL이면 항상 맞고 NONE이면 항상 틀리다.
        boolean success = "NUMBER".equals(kind) ? !top.numberMatches(declaredNumber)
                                                : !top.spiceMatches(declaredSpice);

        int taken = pile.size();
        P winner = success ? challenger : accused;
        P loser = success ? accused : challenger;
        winner.won += taken;

        lastReveal = new Reveal(challenger.seat, accused.seat, kind,
                declaredSpice, declaredNumber, top.spice(), top.number(), success, taken);
        note("🔍 " + challenger.nick + " ▸ " + accused.nick + " 「"
                + ("NUMBER".equals(kind) ? "숫자" : "스파이스") + " 도전」! 실제는 "
                + cardLabel(top) + " → " + (success ? "도전 성공! " : "도전 실패… ")
                + winner.nick + " 더미 " + taken + "장 획득");

        // 진 사람은 덱에서 2장을 가져간다.
        dealOne(loser);
        if (phase == Phase.PLAYING) dealOne(loser);

        pile.clear();
        declaredSpice = -1; declaredNumber = -1; lastPlayerSeat = -1;
        if (phase != Phase.PLAYING) return;

        // 손패를 다 턴 사람이 있으면 트로피(도전을 이겨내고 마지막 장을 낸 경우 포함)
        checkTrophy(accused);
        if (phase != Phase.PLAYING) return;
        checkTrophy(challenger);
        if (phase != Phase.PLAYING) return;

        // 더미를 가져간 사람부터 새 스파이시 게임을 시작한다.
        turnSeat = winner.seat;
        beginPlay();
    }

    /** 아무도 도전하지 않으면 다음 사람 차례로 넘어간다(더미는 쌓인 채 유지). */
    private void resolveNoChallenge() {
        P last = lastPlayerSeat >= 0 ? players.get(lastPlayerSeat) : null;
        if (last != null) {
            checkTrophy(last);
            if (phase != Phase.PLAYING) return;
        }
        nextTurn();
        beginPlay();
    }

    /** 손패가 비었으면 트로피 획득 후 6장 재충전. 2개면 즉시 승리. */
    private void checkTrophy(P p) {
        if (!p.hand.isEmpty() || p.left) return;
        if (trophyLeft <= 0) { endByScore("트로피가 모두 소진되어"); return; }
        trophyLeft--; p.trophies++;
        note("🏆 " + p.nick + " 손패를 모두 내려놓고 트로피 획득! (" + p.trophies + "/" + TROPHY_TO_WIN + ")");
        if (p.trophies >= TROPHY_TO_WIN) {
            phase = Phase.ENDED; winnerSeat = p.seat; winnerLabel = p.nick;
            note("🎉 " + p.nick + " 트로피 " + TROPHY_TO_WIN + "개 달성 · 승리!");
            deadline = 0; return;
        }
        for (int k = 0; k < HAND && phase == Phase.PLAYING; k++) dealOne(p);
        if (phase == Phase.PLAYING && trophyLeft == 0) endByScore("트로피가 모두 소진되어");
    }

    private void endByScore(String why) {
        if (phase == Phase.ENDED) return;
        phase = Phase.ENDED; deadline = 0;
        int best = Integer.MIN_VALUE; P top = null;
        for (P p : players) {
            if (p.left) continue;
            int s = score(p);
            if (s > best) { best = s; top = p; }
        }
        winnerSeat = top == null ? -1 : top.seat;
        winnerLabel = top == null ? "무승부" : top.nick;
        note("📊 " + why + " 점수 계산 · " + (top == null ? "무승부" : top.nick + " 승리! (" + best + "점)"));
    }

    /** 획득 카드 1장 1점 + 트로피 10점 − (옵션) 손에 남은 카드 1장당 1점. */
    int score(P p) { return p.won + p.trophies * 10 - (handPenalty ? p.hand.size() : 0); }

    private void nextTurn() {
        turnSeat = seatAfter(turnSeat);
    }

    /**
     * 좌석 순서상 다음 차례(나간 사람은 건너뜀). 정할 수 없으면 -1.
     *
     * <p>화면의 "다음 차례" 표시도 이걸 그대로 쓴다. 프론트에서 따로 계산하면
     * 나간 사람 처리가 서버와 어긋난다.
     */
    private int seatAfter(int from) {
        int n = players.size();
        if (n == 0 || from < 0) return -1;
        int s = from, guard = 0;
        do { s = (s + 1) % n; guard++; }
        while (guard <= n * 2 && players.get(s).left);
        return players.get(s).left ? -1 : s;
    }

    // ── 봇/타임아웃 ──
    public synchronized void tick() {
        if (phase != Phase.PLAYING || turnSeat < 0) return;
        P cur = players.get(turnSeat);
        if (cur.left) { nextTurn(); beginPlay(); return; }
        long t = now();
        if (turnPhase == TurnPhase.PLAY) {
            if (cur.bot) { if (t >= botAt) botPlay(cur); }
            // 제한시간 없음 모드에서는 사람을 재촉하지 않는다.
            else if (!noTimeLimit() && t >= deadline) autoPlay(cur);
        } else if (turnPhase == TurnPhase.CHALLENGE) {
            if (!botChallengeDone && t >= botAt) botChallengeDecision();
            if (phase != Phase.PLAYING || turnPhase != TurnPhase.CHALLENGE) return;
            // 제한시간이 있으면 마감으로, 없으면 모두가 '통과'했을 때 넘어간다.
            if (noTimeLimit()) { if (allPassed()) resolveNoChallenge(); }
            else if (t >= deadline) resolveNoChallenge();
        }
    }

    /** 시간이 지나면 자동으로 진행한다(가능하면 진실, 아니면 패스). */
    private void autoPlay(P p) {
        if (p.hand.isEmpty()) { doPass(p); return; }
        pickAndPlay(p, 0.0);
    }

    private void botPlay(P p) {
        botAt = now() + BOT_DELAY_MS;
        if (p.hand.isEmpty()) { doPass(p); return; }
        double bluffPref = switch (p.botLevel) { case "EASY" -> 0.15; case "HARD" -> 0.45; default -> 0.3; };
        pickAndPlay(p, bluffPref);
    }

    /**
     * 낼 카드와 선언을 고른다. 진실로 낼 수 있으면 우선 진실을 쓰되(도전당해도 안전),
     * 난이도에 따라 일부러 뻥을 친다. 손패가 많고 뻥칠 여유가 없으면 패스한다.
     */
    private void pickAndPlay(P p, double bluffPref) {
        List<Integer> nums = playableNumbers(), spices = playableSpices();

        // 1) 선언과 완전히 일치하는 카드(진실) — 가장 안전
        if (ThreadLocalRandom.current().nextDouble() >= bluffPref) {
            for (int spice : spices)
                for (int number : nums)
                    for (Card c : p.hand)
                        if (c.spiceMatches(spice) && c.numberMatches(number)) { doPlay(p, c, spice, number); return; }
        }
        // 2) 한쪽만 맞는 카드 — 도전당해도 지목이 갈린다
        for (int spice : spices)
            for (int number : nums)
                for (Card c : p.hand)
                    if (c.spiceMatches(spice) || c.numberMatches(number)) { doPlay(p, c, spice, number); return; }
        // 3) 완전한 거짓말 — 초급 봇은 차라리 패스한다
        if ("EASY".equals(p.botLevel) && ThreadLocalRandom.current().nextBoolean()) { doPass(p); return; }
        Card c = p.hand.get(ThreadLocalRandom.current().nextInt(p.hand.size()));
        int spice = spices.get(ThreadLocalRandom.current().nextInt(spices.size()));
        int number = nums.get(ThreadLocalRandom.current().nextInt(nums.size()));
        doPlay(p, c, spice, number);
    }

    /**
     * 도전 여부를 한 번만 판정한다. 봇마다 독립으로 굴리면 인원이 많을수록 도전이 폭증하므로,
     * '누구든 도전할 확률'을 먼저 정하고 봇 수로 나눠 개인 확률을 구한다.
     */
    private void botChallengeDecision() {
        botChallengeDone = true;
        List<P> bots = new ArrayList<>();
        for (P p : players) if (p.bot && !p.left && p.seat != lastPlayerSeat) bots.add(p);
        if (bots.isEmpty()) return;
        Collections.shuffle(bots, ThreadLocalRandom.current());

        for (P b : bots) {
            // 높은 숫자일수록·더미가 클수록 도전 이득이 크다.
            double agg = declaredNumber >= 9 ? 0.34 : declaredNumber >= 7 ? 0.22 : declaredNumber >= 5 ? 0.13 : 0.07;
            if (pile.size() >= 5) agg *= 1.5; else if (pile.size() >= 3) agg *= 1.2;
            agg *= switch (b.botLevel) { case "EASY" -> 0.6; case "HARD" -> 1.35; default -> 1.0; };
            // 내 손에 같은 숫자가 여럿이면 상대가 그 숫자를 가졌을 확률이 낮다 → 숫자 도전이 유리
            int sameNumber = 0, sameSpice = 0;
            for (Card c : b.hand) {
                if (c.number() == declaredNumber) sameNumber++;
                if (c.spice() == declaredSpice) sameSpice++;
            }
            if (sameNumber >= 2) agg *= 1.5;
            double per = 1 - Math.pow(1 - Math.min(0.85, agg), 1.0 / bots.size());
            if (ThreadLocalRandom.current().nextDouble() < per) {
                String kind = sameNumber > sameSpice ? "NUMBER"
                        : sameSpice > sameNumber ? "SPICE"
                        : (ThreadLocalRandom.current().nextBoolean() ? "NUMBER" : "SPICE");
                resolveChallenge(b, kind);
                return;
            }
            passVotes.add(b.seat);   // 도전 안 하기로 했으면 '통과'로 기록(제한시간 없음 모드 진행용)
        }
    }

    // ── 상태 뷰 ──
    public synchronized SpicyState me(String clientId) {
        touch(); tick();
        P me = byClient(clientId);
        int meSeat = me == null ? -1 : me.seat;

        List<PlayerView> pv = new ArrayList<>();
        for (P p : players) {
            if (p.left && phase == Phase.LOBBY) continue;
            pv.add(new PlayerView(p.seat, p.nick, p.bot, p.host, p == me, p.left,
                    p.hand.size(), p.won, p.trophies, score(p)));
        }
        List<CardView> hand = new ArrayList<>();
        if (me != null) for (Card c : me.hand) hand.add(new CardView(c.id(), c.spice(), c.number()));

        boolean myTurn = phase == Phase.PLAYING && me != null && meSeat == turnSeat && !me.left
                && turnPhase == TurnPhase.PLAY;
        boolean canCh = phase == Phase.PLAYING && turnPhase == TurnPhase.CHALLENGE
                && me != null && !me.left && meSeat != lastPlayerSeat;
        String turnName = phase == Phase.PLAYING && turnSeat >= 0 ? players.get(turnSeat).nick : null;
        int nextSeat = phase == Phase.PLAYING ? seatAfter(turnSeat) : -1;

        return new SpicyState(
                phase.name(),
                phase == Phase.PLAYING && turnPhase != null ? turnPhase.name() : null,
                challengeSec, noTimeLimit(),
                turnPhase == TurnPhase.CHALLENGE ? passVotes.size() : 0,
                turnPhase == TurnPhase.CHALLENGE ? challengerCount() : 0,
                me != null && passVotes.contains(meSeat),
                handPenalty, deckSize, trophyTotal,
                clientId != null && clientId.equals(hostClientId),
                me != null,
                pv, hand,
                turnSeat, turnName, nextSeat, myTurn, meSeat,
                pile.size(), declaredSpice, declaredNumber, lastPlayerSeat,
                myTurn ? playableNumbers() : List.of(),
                myTurn ? playableSpices() : List.of(),
                canCh, lastReveal, draw.size(), trophyLeft,
                lastAction, new ArrayList<>(log),
                winnerSeat, winnerLabel, deadline, now());
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
        else {
            p.left = true;
            if (phase == Phase.PLAYING) {
                int alive = 0; P last = null;
                for (P q : players) if (!q.left) { alive++; last = q; }
                if (alive <= 1) {
                    phase = Phase.ENDED; deadline = 0;
                    winnerSeat = last == null ? -1 : last.seat;
                    winnerLabel = last == null ? "무승부" : last.nick;
                } else if (p.seat == turnSeat && turnPhase == TurnPhase.PLAY) { nextTurn(); beginPlay(); }
            }
        }
        touch();
    }

    // ── 유틸/테스트 ──
    public synchronized Phase phase() { return phase; }
    public int turnSeat() { return turnSeat; }
    public int winnerSeat() { return winnerSeat; }
    int deckSizeForTest() { return deckSize; }
    int trophyTotalForTest() { return trophyTotal; }
    List<P> playersList() { return players; }
    List<Card> pileForTest() { return pile; }
    List<Card> drawForTest() { return draw; }
    int declaredNumberForTest() { return declaredNumber; }
    int declaredSpiceForTest() { return declaredSpice; }
    void setDeclaredForTest(int spice, int number) { declaredSpice = spice; declaredNumber = number; }
    void forcePassChallengeForTest() { if (turnPhase == TurnPhase.CHALLENGE) resolveNoChallenge(); }

    public P byClient(String clientId) { if (clientId == null) return null; for (P p : players) if (clientId.equals(p.clientId)) return p; return null; }

    private static String cardLabel(Card c) {
        String s = c.spice() == ALL ? "🌈 모든 스파이스" : c.spice() == NONE ? "— 스파이스 없음" : SPICE_NAMES[c.spice()];
        String n = c.number() == ALL ? "★ 모든 숫자" : c.number() == NONE ? "— 숫자 없음" : String.valueOf(c.number());
        return s + " " + n;
    }

    private void requireTurn(String clientId) {
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        P p = byClient(clientId);
        if (p == null || p.seat != turnSeat) throw bad("당신 차례가 아닙니다");
    }
    private int activeCount() { int n = 0; for (P p : players) if (!p.left) n++; return n; }
    private void requireHost(String c) { if (!hostClientId.equals(c)) throw bad("방장만 할 수 있습니다"); }
    private String botName() { int n = 1; for (P p : players) if (p.bot) n++; return "봇" + n; }
    private void touch() { lastActive = now(); }
    private static long now() { return System.currentTimeMillis(); }
    private static String clean(String s) { String n = s == null ? "" : s.trim(); if (n.isEmpty()) n = "익명"; return n.length() > 16 ? n.substring(0, 16) : n; }
    private static String normLevel(String s) { String u = s == null ? "NORMAL" : s.toUpperCase(); return switch (u) { case "EASY", "HARD", "NORMAL" -> u; default -> "NORMAL"; }; }
    private static BusinessException bad(String m) { return new BusinessException(ErrorCode.INVALID_INPUT, m); }
}
