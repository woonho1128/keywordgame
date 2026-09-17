package com.wordplay.coup;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.coup.dto.CoupStateResponse;
import com.wordplay.coup.dto.CoupStateResponse.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 쿠(Coup) — 블러핑 카드게임. 2~6인, 봇 포함, 반응(의심/차단) 상태머신.
 * 설계: COUP_DESIGN.md. 인메모리·폴링·lazy tick.
 */
public class CoupGame implements RoomGame {

    enum Phase { LOBBY, PLAYING, ENDED }
    enum Character { DUKE, ASSASSIN, CAPTAIN, AMBASSADOR, CONTESSA }
    enum Action { INCOME, FOREIGN_AID, COUP, TAX, ASSASSINATE, STEAL, EXCHANGE }
    enum Step { CHOOSE_ACTION, CHALLENGE_ACTION, BLOCK_ACTION, CHALLENGE_BLOCK, LOSE_CARD, EXCHANGE_SELECT }
    enum AfterLose { NEXT_TURN, PROCEED_ACTION, BLOCKED_NEXT, APPLY_EFFECT }

    private static final int MAX_PLAYERS = 6;
    private static final int START_COINS = 2;
    private static final long BOT_DELAY = 1300;

    static final class Card { Character ch; boolean revealed; Card(Character c) { ch = c; } }
    static final class Player {
        final String clientId; String nick; boolean bot; String aiLevel = "NORMAL";
        int coins = START_COINS; final List<Card> cards = new ArrayList<>(); boolean alive = true;
        Player(String clientId, String nick) { this.clientId = clientId; this.nick = nick; }
        int influence() { int n = 0; for (Card c : cards) if (!c.revealed) n++; return n; }
        boolean has(Character c) { for (Card k : cards) if (!k.revealed && k.ch == c) return true; return false; }
    }

    // 상태
    private Phase phase = null;
    private long lastActiveMs = System.currentTimeMillis();
    private String hostClientId = null;
    private final List<Player> players = new ArrayList<>();
    private final Map<String, Integer> seats = new HashMap<>();
    private final Set<String> leftClients = new HashSet<>();
    private final List<Character> deck = new ArrayList<>();
    private int reactionSec = 15;
    private int currentSeat = 0;
    private int winnerSeat = -1;
    private long version = 0;
    private final List<String> log = new ArrayList<>();

    // pending(진행 중 액션 상태머신)
    private Step step = Step.CHOOSE_ACTION;
    private int actorSeat = -1;
    private Action action = null;
    private int targetSeat = -1;
    private Character claimChar = null;
    private int blockerSeat = -1;
    private Character blockChar = null;
    private final Set<Integer> responders = new HashSet<>();
    private long deadline = 0;
    private long botActAt = 0;
    private int loserSeat = -1;
    private AfterLose afterLose = AfterLose.NEXT_TURN;
    private List<Character> exchangeDraw = null; // EXCHANGE_SELECT 후보

    // =================== 명령 ===================

    public synchronized CoupStateResponse newGame(String clientId, String nick, Integer reactionSec) {
        reset();
        phase = Phase.LOBBY;
        hostClientId = clientId;
        this.reactionSec = clampReaction(reactionSec);
        addPlayer(clientId, nick, false);
        touch();
        return me(clientId);
    }

    public synchronized CoupStateResponse join(String clientId, String nick) {
        if (phase == null) throw bad("생성된 방이 없습니다");
        if (phase != Phase.LOBBY) throw bad("이미 진행 중이라 참가할 수 없습니다");
        if (!seats.containsKey(clientId)) {
            if (players.size() >= MAX_PLAYERS) throw bad("정원(6명)이 찼습니다");
            addPlayer(clientId, nick, false);
        } else players.get(seats.get(clientId)).nick = trimNick(nick);
        touch();
        return me(clientId);
    }

    public synchronized CoupStateResponse addBot(String clientId, String level) {
        if (phase != Phase.LOBBY) throw bad("대기방에서만 봇을 추가할 수 있습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 추가할 수 있습니다");
        if (players.size() >= MAX_PLAYERS) throw bad("정원(6명)이 찼습니다");
        Player b = new Player("bot::" + System.nanoTime(), "🤖 봇" + (players.size()));
        b.bot = true;
        addPlayerObj(b);
        touch();
        return me(clientId);
    }

    public synchronized CoupStateResponse start(String clientId) {
        if (phase != Phase.LOBBY) throw bad("지금 시작할 수 없습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 시작할 수 있습니다");
        if (players.size() < 2) throw bad("최소 2명이 필요합니다");
        deck.clear();
        for (Character c : Character.values()) for (int i = 0; i < 3; i++) deck.add(c);
        Collections.shuffle(deck, rng());
        for (Player p : players) {
            p.coins = START_COINS; p.alive = true; p.cards.clear();
            p.cards.add(new Card(drawTop())); p.cards.add(new Card(drawTop()));
        }
        currentSeat = 0; winnerSeat = -1; log.clear();
        phase = Phase.PLAYING;
        log.add("게임 시작! " + players.get(0).nick + "님 차례");
        beginTurn();
        touch();
        return me(clientId);
    }

    /** 액션 선언(내 턴, CHOOSE_ACTION). */
    public synchronized CoupStateResponse act(String clientId, String actionStr, Integer target) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        if (step != Step.CHOOSE_ACTION || seatOf(me) != currentSeat) throw bad("당신의 차례가 아닙니다");
        doAct(currentSeat, parseAction(actionStr), target == null ? -1 : target);
        touch();
        return me(clientId);
    }

    /** 액션 실행(내부). 봇도 tick 재진입 없이 이걸 호출. */
    private void doAct(int seat, Action a, int t) {
        Player me = players.get(seat);
        if (me.coins >= 10 && a != Action.COUP) throw bad("코인 10개 이상이면 쿠만 할 수 있습니다");
        boolean needTarget = a == Action.COUP || a == Action.ASSASSINATE || a == Action.STEAL;
        if (needTarget && !isValidTarget(t)) throw bad("대상을 선택하세요");

        actorSeat = seat; action = a; targetSeat = t;
        blockerSeat = -1; blockChar = null; claimChar = null; exchangeDraw = null;
        switch (a) {
            case INCOME -> { me.coins += 1; logf("%s: 소득 +1", me.nick); endTurn(); }
            case FOREIGN_AID -> { logf("%s: 해외원조 시도(+2)", me.nick); openBlockWindow(); }
            case COUP -> {
                if (me.coins < 7) throw bad("코인이 부족합니다(7 필요)");
                me.coins -= 7; logf("%s → %s 쿠!", me.nick, players.get(t).nick);
                loseInfluence(t, AfterLose.NEXT_TURN);
            }
            case TAX -> { claimChar = Character.DUKE; logf("%s: 공작(세금 +3) 주장", me.nick); openChallengeWindow(); }
            case ASSASSINATE -> {
                if (me.coins < 3) throw bad("코인이 부족합니다(3 필요)");
                me.coins -= 3; claimChar = Character.ASSASSIN;
                logf("%s → %s 암살자(암살) 주장", me.nick, players.get(t).nick); openChallengeWindow();
            }
            case STEAL -> { claimChar = Character.CAPTAIN; logf("%s → %s 대장(강탈) 주장", me.nick, players.get(t).nick); openChallengeWindow(); }
            case EXCHANGE -> { claimChar = Character.AMBASSADOR; logf("%s: 대사(교환) 주장", me.nick); openChallengeWindow(); }
        }
    }

    /** 반응(의심/차단/통과). */
    public synchronized CoupStateResponse respond(String clientId, String decision, String blockCardStr) {
        tick();
        Player me = requirePlayer(clientId);
        int s = seatOf(me);
        if (!responders.contains(s)) throw bad("지금 응답할 수 없습니다");
        applyResponse(s, decision, blockCardStr);
        touch();
        return me(clientId);
    }

    /** 영향력 상실 카드 선택. */
    public synchronized CoupStateResponse loseCard(String clientId, int cardIndex) {
        tick();
        Player me = requirePlayer(clientId);
        if (step != Step.LOSE_CARD || seatOf(me) != loserSeat) throw bad("지금 카드를 버릴 수 없습니다");
        revealCard(me, cardIndex);
        afterLoseResolve();
        touch();
        return me(clientId);
    }

    /** 대사 교환: 남길 카드 인덱스(exchangeDraw 기준). */
    public synchronized CoupStateResponse exchange(String clientId, List<Integer> keep) {
        tick();
        Player me = requirePlayer(clientId);
        if (step != Step.EXCHANGE_SELECT || seatOf(me) != actorSeat) throw bad("지금 교환할 수 없습니다");
        doExchange(me, keep);
        touch();
        return me(clientId);
    }

    public synchronized CoupStateResponse me(String clientId) {
        lastActiveMs = System.currentTimeMillis();
        tick();
        return build(clientId);
    }

    // =================== 반응 처리 ===================

    private void applyResponse(int s, String decision, String blockCardStr) {
        String d = decision == null ? "PASS" : decision.toUpperCase();
        if (step == Step.CHALLENGE_ACTION || step == Step.CHALLENGE_BLOCK) {
            if (d.equals("CHALLENGE")) { resolveChallenge(s, step == Step.CHALLENGE_BLOCK); return; }
            responders.remove(s);
            if (responders.isEmpty()) windowAllPassed();
        } else if (step == Step.BLOCK_ACTION) {
            if (d.equals("BLOCK")) {
                Character bc = parseChar(blockCardStr);
                if (!isLegalBlock(s, bc)) throw bad("그 카드로는 차단할 수 없습니다");
                blockerSeat = s; blockChar = bc;
                logf("%s: %s 주장으로 차단", players.get(s).nick, label(bc));
                openBlockChallengeWindow();
                return;
            }
            responders.remove(s);
            if (responders.isEmpty()) windowAllPassed();
        }
    }

    private void windowAllPassed() {
        switch (step) {
            case CHALLENGE_ACTION -> proceedAction();
            case BLOCK_ACTION -> applyEffect();      // 아무도 차단 안 함 → 액션 성사
            case CHALLENGE_BLOCK -> { logf("차단 성공: %s 무효화", actionLabel(action)); endTurn(); }
            default -> {}
        }
    }

    private void resolveChallenge(int challenger, boolean isBlock) {
        int claimantSeat = isBlock ? blockerSeat : actorSeat;
        Character claimed = isBlock ? blockChar : claimChar;
        Player claimant = players.get(claimantSeat);
        boolean hasIt = claimant.has(claimed);
        logf("%s이(가) %s의 %s 주장을 의심!", players.get(challenger).nick, claimant.nick, label(claimed));
        if (hasIt) {
            logf("→ 진짜! %s은(는) 카드를 교체", claimant.nick);
            swapCard(claimant, claimed);
            loseInfluence(challenger, isBlock ? AfterLose.BLOCKED_NEXT : AfterLose.PROCEED_ACTION);
        } else {
            logf("→ 뻥! %s이(가) 영향력 상실", claimant.nick);
            if (!isBlock && action == Action.ASSASSINATE) { players.get(actorSeat).coins += 3; } // 암살 취소 환불
            loseInfluence(claimantSeat, isBlock ? AfterLose.APPLY_EFFECT : AfterLose.NEXT_TURN);
        }
    }

    // =================== 흐름 진행 ===================

    private void openChallengeWindow() {
        step = Step.CHALLENGE_ACTION;
        responders.clear();
        for (Player p : players) if (p.alive && seatOf(p) != actorSeat) responders.add(seatOf(p));
        armWindow();
    }
    private void openBlockWindow() {
        step = Step.BLOCK_ACTION;
        responders.clear();
        if (action == Action.FOREIGN_AID) {
            for (Player p : players) if (p.alive && seatOf(p) != actorSeat) responders.add(seatOf(p));
        } else if (targetSeat >= 0 && players.get(targetSeat).alive) {
            responders.add(targetSeat);
        }
        if (responders.isEmpty()) applyEffect(); else armWindow();
    }
    private void openBlockChallengeWindow() {
        step = Step.CHALLENGE_BLOCK;
        responders.clear();
        for (Player p : players) if (p.alive && seatOf(p) != blockerSeat) responders.add(seatOf(p));
        armWindow();
    }
    private void armWindow() { deadline = System.currentTimeMillis() + reactionSec * 1000L; botActAt = System.currentTimeMillis() + BOT_DELAY; }

    /** 액션 주장 확정 후: 차단 가능하면 차단창, 아니면 효과 적용. */
    private void proceedAction() {
        if (action == Action.ASSASSINATE || action == Action.STEAL) openBlockWindow();
        else applyEffect(); // TAX, EXCHANGE
    }

    private void applyEffect() {
        Player actor = players.get(actorSeat);
        switch (action) {
            case FOREIGN_AID -> { actor.coins += 2; logf("%s: 해외원조 +2", actor.nick); endTurn(); }
            case TAX -> { actor.coins += 3; logf("%s: 세금 +3", actor.nick); endTurn(); }
            case STEAL -> {
                Player t = players.get(targetSeat);
                int amt = Math.min(2, t.coins); t.coins -= amt; actor.coins += amt;
                logf("%s: %s에게서 %d코인 강탈", actor.nick, t.nick, amt); endTurn();
            }
            case ASSASSINATE -> {
                if (targetSeat >= 0 && players.get(targetSeat).alive) loseInfluence(targetSeat, AfterLose.NEXT_TURN);
                else endTurn();
            }
            case EXCHANGE -> {
                exchangeDraw = new ArrayList<>();
                for (Card c : actor.cards) if (!c.revealed) exchangeDraw.add(c.ch);
                exchangeDraw.add(drawTop()); exchangeDraw.add(drawTop());
                step = Step.EXCHANGE_SELECT; loserSeat = -1;
                botActAt = System.currentTimeMillis() + BOT_DELAY;
                logf("%s: 교환할 카드 선택 중", actor.nick);
            }
            default -> endTurn();
        }
    }

    private void doExchange(Player p, List<Integer> keep) {
        int need = 0; for (Card c : p.cards) if (!c.revealed) need++;
        Set<Integer> ks = new HashSet<>(keep == null ? List.of() : keep);
        List<Character> kept = new ArrayList<>();
        for (int i = 0; i < exchangeDraw.size(); i++) if (ks.contains(i)) kept.add(exchangeDraw.get(i));
        if (kept.size() != need) throw bad(need + "장을 선택하세요");
        // 나머지는 덱으로 반환
        List<Character> returned = new ArrayList<>(exchangeDraw);
        for (Character c : kept) returned.remove(c);
        // 살아있는 카드 교체
        int ki = 0;
        for (Card c : p.cards) if (!c.revealed) c.ch = kept.get(ki++);
        deck.addAll(returned); Collections.shuffle(deck, rng());
        exchangeDraw = null;
        logf("%s: 교환 완료", p.nick);
        endTurn();
    }

    // =================== 영향력 상실 ===================

    private void loseInfluence(int seat, AfterLose after) {
        Player p = players.get(seat);
        if (p.influence() <= 0) { afterLose = after; afterLoseResolve(); return; }
        if (p.influence() == 1) {
            for (Card c : p.cards) if (!c.revealed) { c.revealed = true; break; }
            logf("%s: 영향력 1장 공개", p.nick);
            checkEliminated(p);
            afterLose = after; afterLoseResolve();
        } else {
            step = Step.LOSE_CARD; loserSeat = seat; afterLose = after;
            botActAt = System.currentTimeMillis() + BOT_DELAY;
        }
    }

    private void revealCard(Player p, int idx) {
        int seen = 0;
        for (Card c : p.cards) {
            if (c.revealed) continue;
            if (seen == idx) { c.revealed = true; logf("%s: %s 공개(상실)", p.nick, label(c.ch)); checkEliminated(p); return; }
            seen++;
        }
        // 잘못된 인덱스면 첫 카드
        for (Card c : p.cards) if (!c.revealed) { c.revealed = true; checkEliminated(p); return; }
    }

    private void checkEliminated(Player p) {
        if (p.influence() <= 0 && p.alive) { p.alive = false; logf("💀 %s 탈락!", p.nick); }
    }

    private void afterLoseResolve() {
        AfterLose a = afterLose;
        switch (a) {
            case NEXT_TURN -> endTurn();
            case PROCEED_ACTION -> proceedAction();
            case BLOCKED_NEXT -> { logf("차단 성공: %s 무효화", actionLabel(action)); endTurn(); }
            case APPLY_EFFECT -> applyEffect();
        }
    }

    // =================== 턴 ===================

    private void beginTurn() {
        step = Step.CHOOSE_ACTION;
        responders.clear();
        botActAt = System.currentTimeMillis() + BOT_DELAY;
    }

    private void endTurn() {
        // 승리 체크
        int aliveCount = 0, last = -1;
        for (int i = 0; i < players.size(); i++) if (players.get(i).alive) { aliveCount++; last = i; }
        if (aliveCount <= 1) { phase = Phase.ENDED; winnerSeat = last; step = Step.CHOOSE_ACTION;
            logf("🏆 %s 승리!", last >= 0 ? players.get(last).nick : "?"); return; }
        // 다음 살아있는 좌석
        int n = players.size();
        for (int k = 1; k <= n; k++) {
            int s = (currentSeat + k) % n;
            if (players.get(s).alive) { currentSeat = s; break; }
        }
        action = null; targetSeat = -1; claimChar = null; blockerSeat = -1; blockChar = null; exchangeDraw = null;
        beginTurn();
    }

    // =================== tick / 봇 ===================

    private void tick() {
        if (phase != Phase.PLAYING) return;
        int guard = 0;
        while (phase == Phase.PLAYING && guard++ < 300) {
            long now = System.currentTimeMillis();
            switch (step) {
                case CHOOSE_ACTION -> {
                    Player cur = players.get(currentSeat);
                    if (!cur.bot || now < botActAt) return;
                    botAct(cur);
                }
                case CHALLENGE_ACTION, BLOCK_ACTION, CHALLENGE_BLOCK -> {
                    if (now < botActAt) {
                        if (now >= deadline) { forcePassAll(); continue; }
                        return;
                    }
                    Integer botSeat = firstBotResponder();
                    if (botSeat != null) { botRespond(botSeat); continue; }
                    if (now >= deadline) { forcePassAll(); continue; }
                    return; // 사람 대기
                }
                case LOSE_CARD -> {
                    Player p = players.get(loserSeat);
                    if (!p.bot || now < botActAt) return;
                    botLose(p);
                }
                case EXCHANGE_SELECT -> {
                    Player p = players.get(actorSeat);
                    if (!p.bot || now < botActAt) return;
                    botExchangeChoice(p);
                }
            }
        }
    }

    private void forcePassAll() {
        responders.clear();
        windowAllPassed();
    }

    private Integer firstBotResponder() {
        for (int s : new ArrayList<>(responders)) if (players.get(s).bot) return s;
        return null;
    }

    private void botRespond(int s) {
        Player bot = players.get(s);
        if (step == Step.CHALLENGE_ACTION) {
            boolean targeted = (action == Action.ASSASSINATE || action == Action.STEAL) && targetSeat == s;
            double p = targeted ? 0.25 : 0.12;
            if (bot.has(claimChar)) p += 0.10; // 내가 그 카드 있으면 상대가 뻥일 확률↑
            if (roll(p)) { resolveChallenge(s, false); return; }
            responders.remove(s); if (responders.isEmpty()) windowAllPassed();
        } else if (step == Step.CHALLENGE_BLOCK) {
            if (roll(0.2)) { resolveChallenge(s, true); return; }
            responders.remove(s); if (responders.isEmpty()) windowAllPassed();
        } else if (step == Step.BLOCK_ACTION) {
            Character need = neededBlock(bot);
            if (need != null && bot.has(need)) { blockerSeat = s; blockChar = need;
                logf("%s: %s 주장으로 차단", bot.nick, label(need)); openBlockChallengeWindow(); return; }
            // 블러핑 차단(자기가 타깃이고 위험할 때)
            if (need != null && (action == Action.ASSASSINATE ? roll(0.45) : action == Action.STEAL ? roll(0.2) : roll(0.15))) {
                blockerSeat = s; blockChar = need; logf("%s: %s 주장으로 차단", bot.nick, label(need)); openBlockChallengeWindow(); return;
            }
            responders.remove(s); if (responders.isEmpty()) windowAllPassed();
        }
    }

    private Character neededBlock(Player bot) {
        int s = seatOf(bot);
        if (action == Action.FOREIGN_AID) return Character.DUKE;
        if (action == Action.ASSASSINATE && targetSeat == s) return Character.CONTESSA;
        if (action == Action.STEAL && targetSeat == s) return bot.has(Character.CAPTAIN) ? Character.CAPTAIN : Character.AMBASSADOR;
        return null;
    }

    private void botAct(Player bot) {
        int me = seatOf(bot);
        List<Integer> opps = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) if (i != me && players.get(i).alive) opps.add(i);
        if (opps.isEmpty()) { endTurn(); return; }
        int strong = opps.stream().max((a, b) -> {
            int c = Integer.compare(players.get(a).influence(), players.get(b).influence());
            return c != 0 ? c : Integer.compare(players.get(a).coins, players.get(b).coins);
        }).orElse(-1);
        int weak = opps.stream().min((a, b) -> Integer.compare(players.get(a).influence(), players.get(b).influence())).orElse(-1);
        int rich = opps.stream().max((a, b) -> Integer.compare(players.get(a).coins, players.get(b).coins)).orElse(-1);
        try {
            if (bot.coins >= 10) { doAct(me, Action.COUP, strong); return; }
            if (bot.coins >= 7 && roll(0.7)) { doAct(me, Action.COUP, strong); return; }
            if (bot.coins >= 3 && bot.has(Character.ASSASSIN) && weak >= 0 && roll(0.6)) { doAct(me, Action.ASSASSINATE, weak); return; }
            if (bot.has(Character.CAPTAIN) && players.get(rich).coins >= 1 && roll(0.6)) { doAct(me, Action.STEAL, rich); return; }
            if (bot.has(Character.DUKE)) { doAct(me, Action.TAX, -1); return; }
            if (bot.has(Character.AMBASSADOR) && roll(0.4)) { doAct(me, Action.EXCHANGE, -1); return; }
            double r = ThreadLocalRandom.current().nextDouble();
            if (r < 0.35) doAct(me, Action.TAX, -1);            // 블러핑 세금
            else if (r < 0.6) doAct(me, Action.FOREIGN_AID, -1);
            else doAct(me, Action.INCOME, -1);
        } catch (RuntimeException e) {
            doAct(me, Action.INCOME, -1);                       // 안전망
        }
    }

    private void botLose(Player p) {
        // 가치 낮은/중복 카드 공개
        int worstIdx = 0, seen = 0, worstVal = Integer.MAX_VALUE;
        int i = 0;
        for (Card c : p.cards) {
            if (c.revealed) continue;
            int v = cardValue(c.ch);
            if (v < worstVal) { worstVal = v; worstIdx = seen; }
            seen++; i++;
        }
        revealCard(p, worstIdx);
        afterLoseResolve();
    }

    private void botExchangeChoice(Player p) {
        int need = 0; for (Card c : p.cards) if (!c.revealed) need++;
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < exchangeDraw.size(); i++) idx.add(i);
        idx.sort((a, b) -> Integer.compare(cardValue(exchangeDraw.get(b)), cardValue(exchangeDraw.get(a))));
        doExchange(p, idx.subList(0, need));
    }

    private static int cardValue(Character c) {
        return switch (c) { case DUKE -> 5; case CONTESSA -> 4; case CAPTAIN -> 3; case ASSASSIN -> 3; case AMBASSADOR -> 2; };
    }

    // =================== 응답 DTO ===================

    private CoupStateResponse build(String clientId) {
        long now = System.currentTimeMillis();
        if (phase == null) return CoupStateResponse.notStarted(now);
        Integer mySeat = seats.get(clientId);
        boolean joined = mySeat != null;

        List<PlayerView> pv = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            List<String> shown = new ArrayList<>();
            for (Card c : p.cards) shown.add(c.revealed ? c.ch.name() : null);
            pv.add(new PlayerView(i, p.nick, p.bot, p.coins, p.alive, p.influence(), shown, i == currentSeat));
        }
        List<String> myCards = new ArrayList<>();
        if (joined) for (Card c : players.get(mySeat).cards) myCards.add(c.revealed ? null : c.ch.name());

        boolean playing = phase == Phase.PLAYING;
        boolean myTurn = playing && joined && step == Step.CHOOSE_ACTION && mySeat == currentSeat;
        boolean canRespond = playing && joined && responders.contains(mySeat)
                && (step == Step.CHALLENGE_ACTION || step == Step.BLOCK_ACTION || step == Step.CHALLENGE_BLOCK);
        boolean mustLose = playing && joined && step == Step.LOSE_CARD && mySeat == loserSeat;
        boolean mustExchange = playing && joined && step == Step.EXCHANGE_SELECT && mySeat == actorSeat;

        List<String> blockOptions = new ArrayList<>();
        boolean canChallenge = false, canBlock = false;
        if (canRespond) {
            if (step == Step.CHALLENGE_ACTION || step == Step.CHALLENGE_BLOCK) canChallenge = true;
            if (step == Step.BLOCK_ACTION) {
                canBlock = true;
                if (action == Action.FOREIGN_AID) blockOptions.add(Character.DUKE.name());
                else if (action == Action.ASSASSINATE) blockOptions.add(Character.CONTESSA.name());
                else if (action == Action.STEAL) { blockOptions.add(Character.CAPTAIN.name()); blockOptions.add(Character.AMBASSADOR.name()); }
            }
        }
        List<String> exch = mustExchange && exchangeDraw != null
                ? exchangeDraw.stream().map(Enum::name).toList() : List.of();
        int exchKeep = mustExchange ? (int) players.get(mySeat).cards.stream().filter(c -> !c.revealed).count() : 0;

        PendingView pending = playing && step != Step.CHOOSE_ACTION
                ? new PendingView(step.name(), actorSeat, action == null ? null : action.name(), targetSeat,
                    claimChar == null ? null : claimChar.name(), blockerSeat, blockChar == null ? null : blockChar.name(),
                    loserSeat, (step == Step.CHALLENGE_ACTION || step == Step.BLOCK_ACTION || step == Step.CHALLENGE_BLOCK) ? deadline : 0)
                : null;

        List<String> recentLog = log.size() > 12 ? log.subList(log.size() - 12, log.size()) : new ArrayList<>(log);

        return new CoupStateResponse(
                phase.name(), now, reactionSec, clientId.equals(hostClientId), joined,
                joined ? mySeat : -1, joined ? players.get(mySeat).nick : null,
                pv, myCards, currentSeat, step.name(), pending,
                myTurn, canRespond, canChallenge, canBlock, blockOptions, mustLose, mustExchange, exch, exchKeep,
                new ArrayList<>(recentLog), winnerSeat, playerCount(), version);
    }

    // =================== RoomGame ===================

    @Override public synchronized String roomStatus() {
        if (phase == null || phase == Phase.LOBBY) return "WAITING";
        return phase == Phase.ENDED ? "ENDED" : "PLAYING";
    }
    @Override public synchronized int playerCount() {
        return (int) players.stream().filter(p -> !p.bot && !leftClients.contains(p.clientId)).count();
    }
    @Override public synchronized String hostLabel() { return players.isEmpty() ? "" : players.get(0).nick; }
    @Override public synchronized boolean isEnded() { return phase == Phase.ENDED; }
    @Override public synchronized long lastActiveMs() { return lastActiveMs; }
    @Override public synchronized void leave(String clientId) {
        Integer seat = seats.get(clientId);
        if (seat == null) return;
        lastActiveMs = System.currentTimeMillis();
        if (phase == null || phase == Phase.LOBBY) {
            players.remove((int) seat); seats.clear();
            for (int i = 0; i < players.size(); i++) seats.put(players.get(i).clientId, i);
            if (clientId.equals(hostClientId)) hostClientId = players.isEmpty() ? null : players.get(0).clientId;
        } else {
            leftClients.add(clientId);
            Player p = players.get(seat);
            if (p.alive) { for (Card c : p.cards) c.revealed = true; p.alive = false; logf("🚪 %s 나감(탈락)", p.nick); }
            if (phase == Phase.PLAYING) {
                if (step == Step.CHOOSE_ACTION && currentSeat == seat) endTurn();
                else { responders.remove((int) seat); if (!responders.isEmpty() || step == Step.CHOOSE_ACTION) {} }
            }
        }
    }

    // 테스트용
    Step stepForTest() { return step; }
    Phase phaseForTest() { return phase; }
    int coinsForTest(int seat) { return players.get(seat).coins; }
    int influenceForTest(int seat) { return players.get(seat).influence(); }
    void forceDeadlineForTest() { deadline = 1; botActAt = 1; }
    void giveCardsForTest(int seat, Character a, Character b) {
        Player p = players.get(seat); p.cards.clear(); p.cards.add(new Card(a)); p.cards.add(new Card(b));
    }
    void setCoinsForTest(int seat, int coins) { players.get(seat).coins = coins; }
    Character claimForTest() { return claimChar; }

    // =================== 유틸 ===================

    private void addPlayer(String clientId, String nick, boolean bot) { Player p = new Player(clientId, trimNick(nick)); p.bot = bot; addPlayerObj(p); }
    private void addPlayerObj(Player p) { seats.put(p.clientId, players.size()); players.add(p); }
    private Player requirePlayer(String clientId) { Integer s = seats.get(clientId); if (s == null) throw bad("참가하지 않은 기기입니다"); return players.get(s); }
    private int seatOf(Player p) { return seats.get(p.clientId); }
    private boolean isValidTarget(int t) { return t >= 0 && t < players.size() && t != currentSeat && players.get(t).alive; }
    private boolean isLegalBlock(int s, Character bc) {
        if (bc == null) return false;
        if (action == Action.FOREIGN_AID) return bc == Character.DUKE;
        if (action == Action.ASSASSINATE) return targetSeat == s && bc == Character.CONTESSA;
        if (action == Action.STEAL) return targetSeat == s && (bc == Character.CAPTAIN || bc == Character.AMBASSADOR);
        return false;
    }
    private Character drawTop() {
        if (deck.isEmpty()) { for (Character c : Character.values()) deck.add(c); Collections.shuffle(deck, rng()); }
        return deck.remove(deck.size() - 1);
    }
    private void swapCard(Player p, Character shown) {
        for (Card c : p.cards) if (!c.revealed && c.ch == shown) { deck.add(shown); Collections.shuffle(deck, rng()); c.ch = drawTop(); return; }
    }
    private boolean roll(double p) { return ThreadLocalRandom.current().nextDouble() < p; }
    private Random rng() { return new Random(ThreadLocalRandom.current().nextLong()); }
    private void logf(String fmt, Object... args) { log.add(String.format(fmt, args)); if (log.size() > 60) log.remove(0); }
    private void touch() { version++; lastActiveMs = System.currentTimeMillis(); }
    private static int clampReaction(Integer s) { int v = s == null ? 15 : s; return v == 8 || v == 25 ? v : 15; }
    private static Action parseAction(String s) {
        try { return Action.valueOf(s.toUpperCase()); } catch (Exception e) { throw new BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 액션"); }
    }
    private static Character parseChar(String s) {
        try { return Character.valueOf(s.toUpperCase()); } catch (Exception e) { return null; }
    }
    private static String label(Character c) {
        if (c == null) return "?";
        return switch (c) { case DUKE -> "공작"; case ASSASSIN -> "암살자"; case CAPTAIN -> "대장"; case AMBASSADOR -> "대사"; case CONTESSA -> "백작부인"; };
    }
    private static String actionLabel(Action a) {
        if (a == null) return "";
        return switch (a) { case INCOME -> "소득"; case FOREIGN_AID -> "해외원조"; case COUP -> "쿠"; case TAX -> "세금"; case ASSASSINATE -> "암살"; case STEAL -> "강탈"; case EXCHANGE -> "교환"; };
    }
    private void reset() {
        phase = null; hostClientId = null; players.clear(); seats.clear(); leftClients.clear(); deck.clear(); log.clear();
        reactionSec = 15; currentSeat = 0; winnerSeat = -1; version = 0;
        step = Step.CHOOSE_ACTION; actorSeat = -1; action = null; targetSeat = -1; claimChar = null;
        blockerSeat = -1; blockChar = null; responders.clear(); deadline = 0; botActAt = 0; loserSeat = -1;
        afterLose = AfterLose.NEXT_TURN; exchangeDraw = null;
    }
    private static BusinessException bad(String msg) { return new BusinessException(ErrorCode.INVALID_INPUT, msg); }
    private static String trimNick(String nick) { String t = nick == null ? "" : nick.trim(); if (t.isEmpty()) t = "익명"; return t.length() > 16 ? t.substring(0, 16) : t; }
}
