package com.wordplay.ciao;

import com.wordplay.ciao.dto.CiaoState;
import com.wordplay.ciao.dto.CiaoState.PlayerView;
import com.wordplay.ciao.dto.CiaoState.Reveal;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 차오차오(Ciao Ciao) 블러핑 레이스. 2~10인(+봇).
 *
 * 턴마다 통 속에 주사위(1~4, X, X)를 굴려 본인만 확인하고 숫자(1~4)를 선언한다.
 * 거짓말 가능, X면 반드시 거짓말. 다른 플레이어는 의심 창 동안 의심할 수 있고
 * 판정은 1:1(선착 1명): 거짓이면 선언자 말 추락 + 의심자가 선언 값만큼 전진,
 * 진실이면 의심자 말 추락 + 선언자가 실제 값만큼 전진. 아무도 의심 안 하면 선언 값 전진.
 * 다리(10칸)를 넘어간 말이 목표 수에 도달하면 즉시 승리. 말이 다 떨어지면 탈락.
 *
 * 인원 보정: 2~4인 말7/목표3(원작), 5~6인 6/3, 7~8인 5/2, 9~10인 4/2.
 */
public class CiaoGame implements RoomGame {

    public enum Phase { LOBBY, PLAYING, ENDED }
    public enum TurnPhase { ROLL, DECLARE, CHALLENGE }

    static final int MAX_PLAYERS = 10, BRIDGE_LEN = 10;
    static final long BOT_DELAY_MS = 1500;
    static final int TURN_SEC = 30;   // ROLL/DECLARE 각 단계 제한
    static final int DEFAULT_CHALLENGE_SEC = 8, MIN_CHALLENGE_SEC = 3, MAX_CHALLENGE_SEC = 30;

    static int pawnsFor(int n) { return n <= 4 ? 7 : n <= 6 ? 6 : n <= 8 ? 5 : 4; }
    static int goalFor(int n) { return n <= 6 ? 3 : 2; }

    static final class P {
        String clientId, nick, botLevel;
        boolean bot, host, left, eliminated;
        int seat;
        int pawnsLeft, crossed, bridgePos; // bridgePos 0=출발점, 1~10=다리 위
        long lastSeen;
    }

    private final String hostClientId;
    /** 0이면 제한시간 없음 — 아무도 재촉당하지 않고, 의심 창은 모두가 '통과'를 눌러야 넘어간다. */
    private final int challengeSec;
    private Phase phase = Phase.LOBBY;
    private TurnPhase turnPhase = null;
    private final List<P> players = new ArrayList<>();
    private int pawnsPer = 0, goal = 0;
    private int turnSeat = -1;
    private int actual = -1;      // 이번 턴 실제 주사위(1~4, 0=X). 서버만 안다.
    private int declared = -1;    // 선언 값(1~4)
    private Reveal lastReveal = null;
    private final List<String> log = new ArrayList<>();
    private String lastAction = null;
    private int winnerSeat = -1;
    private String winnerLabel = null;
    private long deadline = 0, botAt = 0, lastActive = System.currentTimeMillis();
    private boolean botChallengeDone = false; // 이번 의심 창에서 봇 판단을 이미 했는가
    /** 제한시간 없음 모드에서 이번 의심 창을 '통과'한 좌석들. */
    private final java.util.Set<Integer> passVotes = new java.util.HashSet<>();

    public CiaoGame(String hostClientId, String nick, Integer challengeSecOpt) {
        this.hostClientId = hostClientId;
        int cs = challengeSecOpt == null ? DEFAULT_CHALLENGE_SEC : challengeSecOpt;
        // 0은 '제한 없음'이라는 뜻이라 그대로 둔다.
        this.challengeSec = cs <= 0 ? 0 : Math.max(MIN_CHALLENGE_SEC, Math.min(MAX_CHALLENGE_SEC, cs));
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
        players.removeIf(p -> p.left);
        int n = players.size();
        if (n < 2) throw bad("최소 2명(봇 포함)이 필요합니다");
        pawnsPer = pawnsFor(n); goal = goalFor(n);
        for (int i = 0; i < n; i++) {
            P p = players.get(i);
            p.seat = i; p.eliminated = false;
            p.pawnsLeft = pawnsPer; p.crossed = 0; p.bridgePos = 0;
        }
        phase = Phase.PLAYING;
        winnerSeat = -1; winnerLabel = null; log.clear(); lastReveal = null;
        turnSeat = ThreadLocalRandom.current().nextInt(n);
        note("게임 시작! 말 " + pawnsPer + "개 · " + goal + "개 건너면 승리 (" + n + "인)");
        beginTurn();
    }

    /** 제한시간 없음 모드인가. */
    boolean noTimeLimit() { return challengeSec <= 0; }

    private void beginTurn() {
        turnPhase = TurnPhase.ROLL; actual = -1; declared = -1; botChallengeDone = false;
        passVotes.clear();
        deadline = noTimeLimit() ? 0 : now() + TURN_SEC * 1000L;
        botAt = now() + BOT_DELAY_MS;
    }

    // ── 행동 ──
    public synchronized void roll(String clientId) {
        touch(); tick(); requireTurn(clientId);
        if (turnPhase != TurnPhase.ROLL) throw bad("이미 굴렸습니다");
        doRoll();
    }
    public synchronized void declare(String clientId, int value) {
        touch(); tick(); requireTurn(clientId);
        if (turnPhase != TurnPhase.DECLARE) throw bad("먼저 주사위를 굴리세요");
        if (value < 1 || value > 4) throw bad("1~4 중에 선언하세요");
        // X(0)는 어떤 선언(1~4)과도 다르므로 'X는 반드시 거짓말' 규칙이 자동 성립한다.
        doDeclare(value);
    }
    public synchronized void challenge(String clientId) {
        touch(); tick();
        if (phase != Phase.PLAYING || turnPhase != TurnPhase.CHALLENGE) throw bad("지금은 의심할 수 없습니다");
        P ch = byClient(clientId);
        if (ch == null || ch.left || ch.eliminated) throw bad("의심할 수 없습니다");
        if (ch.seat == turnSeat) throw bad("자기 선언은 의심할 수 없습니다");
        resolveChallenge(ch);
    }

    /**
     * 제한시간 없음 모드에서 '의심하지 않겠다'를 알린다.
     * 의심할 수 있는 사람이 모두 통과하면 선언이 그대로 통과된다(타이머 대신 쓰는 진행 장치).
     */
    public synchronized void passChallenge(String clientId) {
        touch(); tick();
        if (phase != Phase.PLAYING || turnPhase != TurnPhase.CHALLENGE) throw bad("지금은 통과할 수 없습니다");
        P p = byClient(clientId);
        if (p == null || p.left || p.eliminated) throw bad("통과할 수 없습니다");
        if (p.seat == turnSeat) throw bad("자기 선언은 통과할 수 없습니다");
        passVotes.add(p.seat);
        if (allPassed()) resolveNoChallenge();
    }

    /** 의심 가능한 사람 수(선언자 제외, 생존자만). */
    private int challengerCount() {
        int n = 0;
        for (P p : players) if (!p.eliminated && !p.left && p.seat != turnSeat) n++;
        return n;
    }
    private boolean allPassed() {
        for (P p : players) if (!p.eliminated && !p.left && p.seat != turnSeat && !passVotes.contains(p.seat)) return false;
        return true;
    }

    private boolean actualIsX() { return actual == 0; }

    private void doRoll() {
        int f = ThreadLocalRandom.current().nextInt(6); // 0~3 → 1~4, 4~5 → X
        actual = f <= 3 ? f + 1 : 0;
        turnPhase = TurnPhase.DECLARE;
        deadline = noTimeLimit() ? 0 : now() + TURN_SEC * 1000L;
    }
    private void doDeclare(int value) {
        declared = value;
        turnPhase = TurnPhase.CHALLENGE;
        deadline = noTimeLimit() ? 0 : now() + challengeSec * 1000L;
        botAt = now() + BOT_DELAY_MS;
        botChallengeDone = false;
        passVotes.clear();
        note("🎲 " + players.get(turnSeat).nick + " ▸ 「" + value + "」 선언!");
    }

    private void resolveChallenge(P challenger) {
        P cur = players.get(turnSeat);
        boolean lie = actualIsX() || actual != declared;
        lastReveal = new Reveal(cur.seat, challenger.seat, declared, actual, lie);
        String die = actualIsX() ? "X" : String.valueOf(actual);
        if (lie) {
            note("🔍 " + challenger.nick + " 의심! 주사위는 「" + die + "」 → 거짓말! " + cur.nick + " 말 추락 💦");
            fall(cur);
            if (phase == Phase.PLAYING) advance(challenger, declared);
        } else {
            note("🔍 " + challenger.nick + " 의심! 주사위는 「" + die + "」 → 진실… " + challenger.nick + " 말 추락 💦");
            fall(challenger);
            if (phase == Phase.PLAYING) advance(cur, actual);
        }
        if (phase == Phase.PLAYING) endTurn();
    }
    private void resolveNoChallenge() {
        P cur = players.get(turnSeat);
        lastReveal = null;
        advance(cur, declared); // 실제 값은 영원히 비공개 — 선언 값대로 전진
        if (phase == Phase.PLAYING) endTurn();
    }

    /** 말 전진(+건넘/승리 판정). */
    private void advance(P p, int k) {
        p.bridgePos += k;
        if (p.bridgePos > BRIDGE_LEN) {
            p.crossed++; p.pawnsLeft--; p.bridgePos = 0;
            note("🏁 " + p.nick + " 말이 다리를 건넜다! (" + p.crossed + "/" + goal + ")");
            if (p.crossed >= goal) { win(p); return; }
            if (p.pawnsLeft <= 0) { eliminate(p); return; }
        } else {
            note("➡️ " + p.nick + " " + k + "칸 전진 (" + p.bridgePos + "/" + BRIDGE_LEN + ")");
        }
    }
    /** 말 1개 추락(다리 위 말이 있으면 그 말, 없으면 대기 말). */
    private void fall(P p) {
        p.pawnsLeft--; p.bridgePos = 0;
        // 남은 말을 전부 건너보내도 목표에 못 미치면 이미 진 것이므로 그 자리에서 탈락시킨다.
        // (말이 0개가 될 때까지 붙잡아 두면 이길 수 없는 사람이 계속 판을 끌게 된다.)
        if (p.crossed + p.pawnsLeft < goal) eliminate(p);
    }
    private void eliminate(P p) {
        p.eliminated = true;
        note(p.pawnsLeft <= 0
                ? "☠️ " + p.nick + " 말이 다 떨어져 탈락! (건넌 말 " + p.crossed + "개)"
                : "☠️ " + p.nick + " 남은 말 " + p.pawnsLeft + "개로는 " + goal + "개를 채울 수 없어 탈락! (건넌 말 " + p.crossed + "개)");
        P last = null; int n = 0;
        for (P q : players) if (!q.eliminated && !q.left) { n++; last = q; }
        if (n == 1 && last != null) { win(last); return; }
        if (n == 0) { phase = Phase.ENDED; winnerSeat = -1; winnerLabel = "무승부"; deadline = 0; }
    }
    private void win(P p) {
        phase = Phase.ENDED; winnerSeat = p.seat; winnerLabel = p.nick; deadline = 0;
        note("🏆 " + p.nick + " 승리!");
    }

    private void endTurn() {
        if (phase != Phase.PLAYING) return;
        int n = players.size(), guard = 0;
        do { turnSeat = (turnSeat + 1) % n; guard++; }
        while (guard <= n * 2 && (players.get(turnSeat).eliminated || players.get(turnSeat).left));
        beginTurn();
    }

    // ── 봇/타임아웃 ──
    public synchronized void tick() {
        if (phase != Phase.PLAYING || turnSeat < 0) return;
        P cur = players.get(turnSeat);
        if (cur.left || cur.eliminated) { endTurn(); return; }
        long t = now();
        if (turnPhase == TurnPhase.ROLL || turnPhase == TurnPhase.DECLARE) {
            if (cur.bot) { if (t >= botAt) botRollDeclare(cur); }
            // 제한시간 없음 모드에서는 사람을 재촉하지 않는다.
            else if (!noTimeLimit() && t >= deadline) autoRollDeclare();
        } else if (turnPhase == TurnPhase.CHALLENGE) {
            if (!botChallengeDone && t >= botAt) botChallengeDecision();
            if (phase != Phase.PLAYING || turnPhase != TurnPhase.CHALLENGE) return;
            // 제한시간이 있으면 마감으로, 없으면 모두가 '통과'했을 때 넘어간다.
            if (noTimeLimit()) { if (allPassed()) resolveNoChallenge(); }
            else if (t >= deadline) resolveNoChallenge();
        }
    }

    /** 사람 턴 타임아웃: 자동 굴림 + 진실(X면 무작위) 선언. */
    private void autoRollDeclare() {
        if (turnPhase == TurnPhase.ROLL) doRoll();
        doDeclare(actualIsX() ? 1 + ThreadLocalRandom.current().nextInt(4) : actual);
    }

    private void botRollDeclare(P p) {
        botAt = now() + BOT_DELAY_MS;
        if (turnPhase == TurnPhase.ROLL) { doRoll(); return; } // 다음 tick에 선언(굴리는 연출 시간)
        int v;
        double bluff = switch (p.botLevel) { case "EASY" -> 0.10; case "HARD" -> 0.35; default -> 0.20; };
        if (actualIsX()) {
            // 강제 거짓말. 고급 봇은 큰 수를 선호하되 1~4를 모두 쓴다 —
            // 2~3만 부르면 "고급 봇의 1은 무조건 진실"이라는 정보가 새어 공략당한다.
            v = "HARD".equals(p.botLevel) ? weightedPick(1, 3, 3, 2)
                    : 1 + ThreadLocalRandom.current().nextInt(4);
        } else if (ThreadLocalRandom.current().nextDouble() < bluff && actual < 4) {
            v = actual + 1 + ThreadLocalRandom.current().nextInt(4 - actual); // 과장
        } else {
            v = actual;
        }
        doDeclare(v);
    }

    /** w1~w4 가중치로 1~4 중 하나를 고른다. */
    private static int weightedPick(int w1, int w2, int w3, int w4) {
        int total = w1 + w2 + w3 + w4, r = ThreadLocalRandom.current().nextInt(total);
        if ((r -= w1) < 0) return 1;
        if ((r -= w2) < 0) return 2;
        return (r - w3) < 0 ? 3 : 4;
    }

    /** 의심 창에서 봇들의 의심 여부를 한 번만 판정. */
    private void botChallengeDecision() {
        botChallengeDone = true;
        P cur = players.get(turnSeat);
        List<P> bots = new ArrayList<>();
        for (P p : players) if (p.bot && !p.eliminated && !p.left && p.seat != turnSeat) bots.add(p);
        java.util.Collections.shuffle(bots, ThreadLocalRandom.current());
        if (bots.isEmpty()) return;
        boolean crossing = cur.bridgePos + declared > BRIDGE_LEN;          // 이 선언이 통하면 건넘
        boolean reaching = crossing && cur.crossed + 1 >= goal;            // 통하면 승리
        for (P b : bots) {
            // 먼저 '이 선언을 누구든 의심할 확률'을 정하고 봇 수로 나눠 개인 확률을 구한다.
            // 봇마다 독립으로 굴리면 인원이 많을수록 의심이 폭증해, 서로 헛의심하다
            // 말을 전부 잃고 아무도 다리를 못 건너는 판이 된다.
            double agg = switch (declared) { case 4 -> 0.34; case 3 -> 0.20; case 2 -> 0.10; default -> 0.05; };
            agg *= switch (b.botLevel) { case "EASY" -> 0.6; case "HARD" -> 1.3; default -> 1.0; };
            if (reaching) agg *= 2.0; else if (crossing) agg *= 1.4;
            if (b.pawnsLeft <= 2) agg *= 0.4;   // 말이 얼마 안 남으면 몸을 사린다
            double per = 1 - Math.pow(1 - Math.min(0.85, agg), 1.0 / bots.size());
            if (ThreadLocalRandom.current().nextDouble() < per) { resolveChallenge(b); return; }
            passVotes.add(b.seat);   // 의심 안 하기로 했으면 '통과'로 기록(제한시간 없음 모드 진행용)
        }
    }

    // ── 상태 뷰 ──
    public synchronized CiaoState me(String clientId) {
        touch(); tick();
        P me = byClient(clientId);
        int meSeat = me == null ? -1 : me.seat;

        List<PlayerView> pv = new ArrayList<>();
        for (P p : players) {
            if (p.left && phase == Phase.LOBBY) continue;
            pv.add(new PlayerView(p.seat, p.nick, p.bot, p.host, p == me, p.left, p.eliminated,
                    p.pawnsLeft, p.crossed, p.bridgePos));
        }
        boolean myTurn = phase == Phase.PLAYING && me != null && meSeat == turnSeat && !me.eliminated && !me.left;
        String turnName = phase == Phase.PLAYING && turnSeat >= 0 ? players.get(turnSeat).nick : null;
        int myRoll = myTurn && actual >= 0 ? actual : -1;
        boolean canCh = phase == Phase.PLAYING && turnPhase == TurnPhase.CHALLENGE
                && me != null && !me.eliminated && !me.left && meSeat != turnSeat;

        return new CiaoState(
                phase.name(),
                phase == Phase.PLAYING && turnPhase != null ? turnPhase.name() : null,
                BRIDGE_LEN, pawnsPer, goal, challengeSec,
                noTimeLimit(),
                turnPhase == TurnPhase.CHALLENGE ? passVotes.size() : 0,
                turnPhase == TurnPhase.CHALLENGE ? challengerCount() : 0,
                me != null && passVotes.contains(meSeat),
                clientId != null && clientId.equals(hostClientId),
                me != null,
                pv, turnSeat, turnName, myTurn, meSeat,
                turnPhase == TurnPhase.CHALLENGE ? declared : -1,
                myRoll, canCh, lastReveal, lastAction, new ArrayList<>(log),
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
                p.eliminated = true;
                P last = null; int n = 0;
                for (P q : players) if (!q.eliminated && !q.left) { n++; last = q; }
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
    int pawnsPer() { return pawnsPer; }
    int goal() { return goal; }
    int actualForTest() { return actual; }
    void setRollForTest(int v) { actual = v; turnPhase = TurnPhase.DECLARE; }
    void expireChallengeForTest() { if (turnPhase == TurnPhase.CHALLENGE) resolveNoChallenge(); }
    List<P> playersList() { return players; }
    public P byClient(String clientId) { if (clientId == null) return null; for (P p : players) if (clientId.equals(p.clientId)) return p; return null; }

    private void requireTurn(String clientId) {
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        P p = byClient(clientId);
        if (p == null || p.seat != turnSeat) throw bad("당신 차례가 아닙니다");
        if (p.eliminated) throw bad("이미 탈락했습니다");
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
