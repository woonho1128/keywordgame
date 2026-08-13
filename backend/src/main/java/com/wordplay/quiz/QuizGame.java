package com.wordplay.quiz;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.quiz.dto.QuizState;
import com.wordplay.quiz.dto.QuizState.PlayerView;
import com.wordplay.quiz.dto.QuizState.Reveal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 상식 퀴즈 대결. 1~10인. 봇 없음(혼자서도 시작된다).
 *
 * <p>모두에게 같은 문제를 동시에 내고, 각자 답한다. 객관식은 보기를 고르고 주관식은
 * 직접 쓴다. 빨리 맞히면 점수가 더 붙어(끄투 방식) 아는 문제를 먼저 치는 게 이득이다.
 * 주관식은 초성 힌트를 주고 배점을 {@value #TEXT_BONUS_PCT}% 더 준다.
 *
 * <p>전원이 답하면 제한시간을 기다리지 않고 바로 정답을 공개한다 — 혼자 할 때
 * 매 문제 20초를 기다리면 지루하다.
 */
public class QuizGame implements RoomGame {

    public enum Phase { LOBBY, ASKING, REVEAL, ENDED }

    static final int MAX_PLAYERS = 10;
    static final int BASE_SCORE = 100;          // 맞히면 기본 점수
    static final int SPEED_SCORE = 100;         // 빨리 맞힌 만큼 추가로 받는 최대 점수
    static final int TEXT_BONUS_PCT = 50;       // 주관식 가산 비율
    static final long REVEAL_MS = 4000;         // 정답 공개 후 다음 문제까지
    static final int DEFAULT_SEC = 20, MIN_SEC = 5, MAX_SEC = 60;
    static final int DEFAULT_ROUNDS = 10, MIN_ROUNDS = 3, MAX_ROUNDS = 30;

    static final class P {
        String clientId, nick;
        boolean host, left;
        int seat, score, correct, streak, bestStreak;
        /** 이번 문제에 낸 답. 아직 안 냈으면 null. */
        String answer;
        boolean answeredRight;
        long answeredAt;
    }

    private final String hostClientId;
    private final QuizBank bank;
    private final int level, rounds, questionSec;

    private Phase phase = Phase.LOBBY;
    private final List<P> players = new ArrayList<>();
    private final List<QuizQuestion> questions = new ArrayList<>();
    private int index = -1;                     // 현재 문제 번호(0-based)
    private long deadline = 0, revealAt = 0, askedAt = 0;
    private Reveal lastReveal = null;
    private final List<String> log = new ArrayList<>();
    private long lastActive = System.currentTimeMillis();

    public QuizGame(String hostClientId, String nick, Integer levelOpt, Integer roundsOpt,
                    Integer secOpt, QuizBank bank) {
        this.hostClientId = hostClientId;
        this.bank = bank;
        this.level = clamp(levelOpt, 1, 10, 5);
        this.rounds = clamp(roundsOpt, MIN_ROUNDS, MAX_ROUNDS, DEFAULT_ROUNDS);
        this.questionSec = clamp(secOpt, MIN_SEC, MAX_SEC, DEFAULT_SEC);
        P host = new P();
        host.clientId = hostClientId; host.nick = clean(nick); host.host = true;
        players.add(host);
    }

    private static int clamp(Integer v, int lo, int hi, int dflt) {
        if (v == null) return dflt;
        return Math.max(lo, Math.min(hi, v));
    }

    private void note(String s) { log.add(s); if (log.size() > 80) log.remove(0); }

    // ── 로비 ──
    public synchronized void join(String clientId, String nick) {
        touch();
        P e = byClient(clientId);
        if (e != null) { e.left = false; e.nick = clean(nick); return; }
        if (phase != Phase.LOBBY) throw bad("이미 시작된 방입니다");
        if (activeCount() >= MAX_PLAYERS) throw bad("정원(10명)이 찼습니다");
        P p = new P(); p.clientId = clientId; p.nick = clean(nick);
        players.add(p);
    }

    /** 시작. 혼자여도 된다(연습·심심풀이 용도). */
    public synchronized void start(String clientId) {
        touch();
        if (!hostClientId.equals(clientId)) throw bad("방장만 시작할 수 있습니다");
        if (phase != Phase.LOBBY) throw bad("이미 시작되었습니다");
        players.removeIf(p -> p.left);
        if (players.isEmpty()) throw bad("참가자가 없습니다");

        questions.clear();
        questions.addAll(bank.take(level, rounds));
        if (questions.isEmpty()) throw bad("문제를 준비하지 못했습니다. 잠시 뒤 다시 시도해 주세요");

        for (int i = 0; i < players.size(); i++) {
            P p = players.get(i);
            p.seat = i; p.score = 0; p.correct = 0; p.streak = 0; p.bestStreak = 0;
            p.answer = null; p.answeredRight = false; p.answeredAt = 0;
        }
        log.clear();
        note("퀴즈 시작! 난이도 " + level + " · " + questions.size() + "문제");
        index = -1;
        nextQuestion();
    }

    private void nextQuestion() {
        index++;
        if (index >= questions.size()) { finish(); return; }
        for (P p : players) { p.answer = null; p.answeredRight = false; p.answeredAt = 0; }
        phase = Phase.ASKING;
        askedAt = now();
        deadline = askedAt + questionSec * 1000L;
        revealAt = 0;
        lastReveal = null;
    }

    // ── 답 제출 ──

    /** 객관식은 보기 번호(0~3)를 문자열로, 주관식은 쓴 답을 그대로 보낸다. */
    public synchronized void answer(String clientId, String given) {
        touch();
        if (phase != Phase.ASKING) throw bad("지금은 답할 수 없습니다");
        P p = byClient(clientId);
        if (p == null || p.left) throw bad("참가자가 아닙니다");
        if (p.answer != null) throw bad("이미 답했습니다");

        QuizQuestion q = current();
        if (q == null) return;
        String text = given == null ? "" : given.trim();
        if (text.isEmpty()) throw bad("답을 입력해 주세요");
        if (text.length() > 60) text = text.substring(0, 60);

        // 객관식은 보기 번호로 온다. 범위를 벗어나면 오답 처리(빈 답과 구분).
        boolean right;
        if (q.kind() == QuizQuestion.Kind.CHOICE) {
            int pick = -1;
            try { pick = Integer.parseInt(text); } catch (NumberFormatException ignore) { }
            right = pick == q.answerIndex();
            p.answer = String.valueOf(pick);
        } else {
            right = q.accepts(text);
            p.answer = text;
        }
        p.answeredRight = right;
        p.answeredAt = now();

        if (right) {
            int gained = scoreFor(q, p.answeredAt);
            p.score += gained;
            p.correct++;
            p.streak++;
            p.bestStreak = Math.max(p.bestStreak, p.streak);
            note((index + 1) + "번 " + p.nick + " 정답 +" + gained);
        } else {
            p.streak = 0;
            note((index + 1) + "번 " + p.nick + " 오답");
        }
        if (allAnswered()) reveal();
    }

    /**
     * 점수. 기본점 + 남은 시간 비례 속도점, 주관식은 가산.
     *
     * <p>속도점을 남은 시간 비율로 주면 제한시간을 길게 잡은 방이 유리해지지 않는다.
     */
    int scoreFor(QuizQuestion q, long at) {
        double leftRatio = questionSec <= 0 ? 0
                : Math.max(0, Math.min(1, (deadline - at) / (questionSec * 1000.0)));
        int base = BASE_SCORE + (int) Math.round(SPEED_SCORE * leftRatio);
        if (q.kind() == QuizQuestion.Kind.TEXT) base = base * (100 + TEXT_BONUS_PCT) / 100;
        return base;
    }

    private boolean allAnswered() {
        for (P p : players) if (!p.left && p.answer == null) return false;
        return true;
    }

    private void reveal() {
        QuizQuestion q = current();
        if (q == null) { finish(); return; }
        phase = Phase.REVEAL;
        revealAt = now() + REVEAL_MS;
        deadline = revealAt;

        List<Integer> rightSeats = new ArrayList<>();
        Map<Integer, String> given = new HashMap<>();
        for (P p : players) {
            if (p.left) continue;
            if (p.answeredRight) rightSeats.add(p.seat);
            if (p.answer != null) given.put(p.seat, displayAnswer(q, p.answer));
        }
        lastReveal = new Reveal(index, q.answerLabel(), q.explain(), List.copyOf(rightSeats), Map.copyOf(given));

        // 다음 판을 위해 창고를 미리 채워둔다(문제 사이 빈 시간을 활용).
        if (bank != null) bank.prewarm(level);
    }

    /** 제출한 답을 사람이 읽을 형태로. 객관식 번호는 보기 문장으로 바꾼다. */
    private String displayAnswer(QuizQuestion q, String raw) {
        if (q.kind() != QuizQuestion.Kind.CHOICE) return raw;
        try {
            int i = Integer.parseInt(raw);
            if (i >= 0 && i < q.choices().size()) return q.choices().get(i);
        } catch (NumberFormatException ignore) { }
        return "무응답";
    }

    private void finish() {
        phase = Phase.ENDED;
        deadline = 0; revealAt = 0;
        P best = null;
        for (P p : players) if (!p.left && (best == null || p.score > best.score)) best = p;
        note(best == null ? "게임 종료" : "🏁 " + best.nick + " 우승! " + best.score + "점");
    }

    // ── 타이머 ──
    public synchronized void tick() {
        long t = now();
        if (phase == Phase.ASKING && t >= deadline) reveal();
        else if (phase == Phase.REVEAL && t >= revealAt) nextQuestion();
    }

    // ── 상태 뷰 ──
    public synchronized QuizState me(String clientId) {
        touch(); tick();
        P me = byClient(clientId);
        int meSeat = me == null ? -1 : me.seat;

        List<PlayerView> pv = new ArrayList<>();
        for (P p : players) {
            if (p.left && phase == Phase.LOBBY) continue;
            // 진행 중에는 남이 무엇을 썼는지 숨긴다(정답 공개 때 lastReveal로 함께 보여준다).
            pv.add(new PlayerView(p.seat, p.nick, p.host, p == me, p.left,
                    p.score, p.correct, p.streak, p.bestStreak, p.answer != null));
        }

        QuizQuestion q = current();
        boolean asking = phase == Phase.ASKING;
        return new QuizState(
                phase.name(), level, questions.size(), index + 1, questionSec,
                clientId != null && clientId.equals(hostClientId), me != null,
                pv,
                q == null ? null : q.topic(),
                q == null ? null : q.kind().name(),
                q == null ? null : q.question(),
                q == null || q.kind() != QuizQuestion.Kind.CHOICE ? List.of() : q.choices(),
                q == null ? null : q.hint(),
                me != null ? me.answer : null,
                me != null && me.answer != null && me.answeredRight,
                asking && me != null && me.answer == null && !me.left,
                phase == Phase.REVEAL ? lastReveal : null,
                new ArrayList<>(log), deadline, now());
    }

    private QuizQuestion current() {
        return index >= 0 && index < questions.size() ? questions.get(index) : null;
    }

    // ── RoomGame ──
    @Override public String roomStatus() {
        return switch (phase) { case LOBBY -> "WAITING"; case ENDED -> "ENDED"; default -> "PLAYING"; };
    }
    @Override public int playerCount() { int n = 0; for (P p : players) if (!p.left) n++; return n; }
    @Override public String hostLabel() { for (P p : players) if (p.host) return p.nick; return "-"; }
    @Override public boolean isEnded() { return phase == Phase.ENDED; }
    @Override public long lastActiveMs() { return lastActive; }
    @Override public synchronized void leave(String clientId) {
        P p = byClient(clientId); if (p == null) return;
        if (phase == Phase.LOBBY) players.remove(p);
        else {
            p.left = true;
            // 남은 사람이 다 답했으면 기다릴 이유가 없다.
            if (phase == Phase.ASKING && allAnswered()) reveal();
            boolean anyone = false;
            for (P q : players) if (!q.left) anyone = true;
            if (!anyone) finish();
        }
        touch();
    }

    // ── 유틸/테스트 ──
    public synchronized Phase phase() { return phase; }
    int index() { return index; }
    List<P> playersList() { return players; }
    List<QuizQuestion> questionsList() { return questions; }
    void expireForTest() { deadline = 0; revealAt = 0; }
    public P byClient(String clientId) {
        if (clientId == null) return null;
        for (P p : players) if (clientId.equals(p.clientId)) return p;
        return null;
    }
    private int activeCount() { int n = 0; for (P p : players) if (!p.left) n++; return n; }
    private void touch() { lastActive = now(); }
    private static long now() { return System.currentTimeMillis(); }
    private static String clean(String s) {
        String n = s == null ? "" : s.trim();
        if (n.isEmpty()) n = "익명";
        return n.length() > 16 ? n.substring(0, 16) : n;
    }
    private static BusinessException bad(String m) { return new BusinessException(ErrorCode.INVALID_INPUT, m); }
}
