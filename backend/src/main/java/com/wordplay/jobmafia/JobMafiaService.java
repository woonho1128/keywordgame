package com.wordplay.jobmafia;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.jobmafia.dto.JobMafiaStateResponse;
import com.wordplay.jobmafia.dto.JobMafiaStateResponse.PlayerView;
import com.wordplay.jobmafia.dto.JobMafiaStateResponse.VoteView;
import com.wordplay.jobmafia.dto.NewJobMafiaRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 직업 마피아(FEIGN 스타일). 인메모리 단일 전역 방, 타이머 자동 진행.
 *
 * 직업 6종:
 *  - 시민/경찰/의사/정신병자 = 시민팀 (마피아 전멸 시 승리)
 *  - 마피아 = 마피아팀 (마피아 수 ≥ 생존 나머지 시 승리)
 *  - 관종 = 중립 (낮 투표로 자기가 처형되면 즉시 단독 승리)
 *
 * 정신병자: 본인에겐 가짜 직업(경찰/의사)으로 보이고 능력은 효과가 없다(경찰이면 결과가 랜덤).
 */
public class JobMafiaService implements RoomGame {

    enum Phase { LOBBY, NIGHT, MORNING, DISCUSS, VOTE, DEFENSE, FINAL_VOTE, EXECUTE, ENDED }
    // 시민 능력직: 경찰·의사·관찰자(대상의 밤 지목 확인)·봉쇄자(대상 밤 능력 무효)
    // 마피아 능력종(능력/킬 택1): 경찰마피아·관찰자마피아·봉쇄자마피아 / 그림자마피아(살해+정체은폐)
    enum Role { CITIZEN, POLICE, DOCTOR, PSYCHO, OBSERVER, BLOCKER,
                MAFIA, MAFIA_COP, MAFIA_SHADOW, MAFIA_OBSERVER, MAFIA_BLOCKER, ATTENTION, THIEF }

    private static boolean isMafia(Role r) {
        return r == Role.MAFIA || r == Role.MAFIA_COP || r == Role.MAFIA_SHADOW
                || r == Role.MAFIA_OBSERVER || r == Role.MAFIA_BLOCKER;
    }
    /** 능력/킬 택1이 가능한 마피아(밤마다 모드 선택). */
    private static boolean isAbilityMafia(Role r) {
        return r == Role.MAFIA_COP || r == Role.MAFIA_OBSERVER || r == Role.MAFIA_BLOCKER;
    }

    private static final long MORNING_MS = 6_000;
    private static final long EXECUTE_MS = 6_000;
    private long defenseMs = 20_000, finalVoteMs = 20_000; // 최후변론·사형투표 시간

    private static final class Player {
        final String clientId;
        String nick;
        Role role;
        boolean alive = true;
        Player(String clientId, String nick) { this.clientId = clientId; this.nick = nick; }
    }

    private Phase phase = null;
    private long phaseEndsAt = 0;
    private long round = 0;
    private long lastActiveMs = System.currentTimeMillis();
    private String hostClientId = null;
    private final List<Player> players = new ArrayList<>();
    private final Map<String, Integer> clientSeats = new HashMap<>();
    private final Set<String> leftClients = new HashSet<>();

    // 설정 (직업 인원 범위 — 시작 시 범위 안에서 랜덤)
    private long nightMs = 60_000, discussMs = 90_000, voteMs = 30_000;
    private int mafiaMin = 1, mafiaMax = 2;
    private int psychoMin = 0, psychoMax = 1;
    private int attentionMin = 0, attentionMax = 1;
    private int thiefMin = 0, thiefMax = 1;
    private boolean neutralGrouped = false;   // 중립(관종·도적꾼) 통합 랜덤
    private int neutralMin = 0, neutralMax = 1;
    private int mafiaCopMin = 0, mafiaCopMax = 0;       // 경찰마피아 인원(마피아 총원 안에서 배정)
    private int mafiaShadowMin = 0, mafiaShadowMax = 0; // 그림자마피아 인원
    private int observerMin = 0, observerMax = 0;       // 시민 관찰자
    private int blockerMin = 0, blockerMax = 0;         // 시민 봉쇄자
    private int mafiaObserverMin = 0, mafiaObserverMax = 0; // 관찰자마피아
    private int mafiaBlockerMin = 0, mafiaBlockerMax = 0;   // 봉쇄자마피아
    private boolean abilityIndependentKill = false;     // 능력마피아 독립 킬 모드

    private final Map<Integer, Boolean> mafiaAbilityMode = new HashMap<>(); // 능력마피아 좌석 -> 이번 밤 능력모드(true)/살해모드(false)
    private final Set<Integer> concealedSeats = new HashSet<>();                // 그림자마피아에게 살해돼 정체가 은폐된 좌석
    private final Set<Integer> blockedSeats = new HashSet<>();                  // 이번 밤 봉쇄자에게 능력이 막힌 좌석
    private boolean revealOnDeath = true;

    // 밤 상태
    private final Map<Integer, Integer> nightTargetBySeat = new HashMap<>(); // 각자 이번 밤 지목(표시용)
    private final List<String> copLog = new ArrayList<>();               // 진짜 경찰 조사 기록
    private final Map<Integer, List<String>> psychoCopLogs = new HashMap<>(); // 좌석별 가짜 경찰(정신병자) 기록

    private List<String> psychoCopLogBySeat(int seat) {
        return psychoCopLogs.computeIfAbsent(seat, k -> new ArrayList<>());
    }
    private final Set<Integer> nightActed = new HashSet<>();

    // 정신병자: seat -> 본인에게 보일 가짜 직업(경찰/의사)
    private final Map<Integer, Role> psychoFakeRoles = new HashMap<>();

    // 투표
    private final Map<Integer, Integer> votes = new HashMap<>();
    private int accusedSeat = -1;                                  // 최다 득표로 재판대에 오른 좌석
    private final Map<Integer, Boolean> finalVotes = new HashMap<>(); // 좌석 -> 사형(true)/생존(false)

    // 결과
    private String nightMessage = null;
    private int nightDeadSeat = -1;
    private int executedSeat = -1;
    private String winner = null;

    // 이력
    private final List<String> history = new ArrayList<>();             // 공개 진행 이력
    private final Map<Integer, List<String>> myLogs = new HashMap<>();  // 좌석별 개인 이력
    private List<String> myLog(int seat) { return myLogs.computeIfAbsent(seat, k -> new ArrayList<>()); }

    // =================== 명령 ===================

    public synchronized JobMafiaStateResponse newGame(String clientId, NewJobMafiaRequest req) {
        reset();
        phase = Phase.LOBBY;
        hostClientId = clientId;
        nightMs = clampSec(req.nightSec(), 20, 180, 60) * 1000L;
        discussMs = clampSec(req.discussSec(), 15, 300, 90) * 1000L;
        voteMs = clampSec(req.voteSec(), 10, 120, 30) * 1000L;
        defenseMs = clampSec(req.defenseSec(), 5, 120, 20) * 1000L;
        finalVoteMs = clampSec(req.finalVoteSec(), 5, 120, 20) * 1000L;
        mafiaMin = clampInt(req.mafiaMin(), 0, 6, 1);
        mafiaMax = clampInt(req.mafiaMax(), mafiaMin, 6, Math.max(mafiaMin, 2));
        psychoMin = clampInt(req.psychoMin(), 0, 4, 0);
        psychoMax = clampInt(req.psychoMax(), psychoMin, 4, Math.max(psychoMin, 1));
        attentionMin = clampInt(req.attentionMin(), 0, 4, 0);
        attentionMax = clampInt(req.attentionMax(), attentionMin, 4, Math.max(attentionMin, 1));
        thiefMin = clampInt(req.thiefMin(), 0, 4, 0);
        thiefMax = clampInt(req.thiefMax(), thiefMin, 4, Math.max(thiefMin, 1));
        neutralGrouped = req.neutralGrouped() != null && req.neutralGrouped();
        neutralMin = clampInt(req.neutralMin(), 0, 6, 0);
        neutralMax = clampInt(req.neutralMax(), neutralMin, 6, Math.max(neutralMin, 1));
        mafiaCopMin = clampInt(req.mafiaCopMin(), 0, 4, 0);
        mafiaCopMax = clampInt(req.mafiaCopMax(), mafiaCopMin, 4, mafiaCopMin);
        mafiaShadowMin = clampInt(req.mafiaShadowMin(), 0, 4, 0);
        mafiaShadowMax = clampInt(req.mafiaShadowMax(), mafiaShadowMin, 4, mafiaShadowMin);
        observerMin = clampInt(req.observerMin(), 0, 4, 0);
        observerMax = clampInt(req.observerMax(), observerMin, 4, observerMin); // 기본 off
        blockerMin = clampInt(req.blockerMin(), 0, 4, 0);
        blockerMax = clampInt(req.blockerMax(), blockerMin, 4, blockerMin);   // 기본 off
        mafiaObserverMin = clampInt(req.mafiaObserverMin(), 0, 4, 0);
        mafiaObserverMax = clampInt(req.mafiaObserverMax(), mafiaObserverMin, 4, mafiaObserverMin);
        mafiaBlockerMin = clampInt(req.mafiaBlockerMin(), 0, 4, 0);
        mafiaBlockerMax = clampInt(req.mafiaBlockerMax(), mafiaBlockerMin, 4, mafiaBlockerMin);
        abilityIndependentKill = req.abilityIndependentKill() != null && req.abilityIndependentKill();
        addPlayer(clientId, req.nick());
        return me(clientId);
    }

    public synchronized JobMafiaStateResponse join(String clientId, String nick) {
        if (phase == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "생성된 방이 없습니다");
        if (phase != Phase.LOBBY) throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 진행 중이라 참가할 수 없습니다");
        if (!clientSeats.containsKey(clientId)) {
            if (players.size() >= 12) throw new BusinessException(ErrorCode.INVALID_INPUT, "정원(12명)이 찼습니다");
            addPlayer(clientId, nick);
        } else {
            players.get(clientSeats.get(clientId)).nick = trimNick(nick);
        }
        return me(clientId);
    }

    public synchronized JobMafiaStateResponse start(String clientId) {
        if (phase != Phase.LOBBY) throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 시작할 수 없습니다");
        if (!clientId.equals(hostClientId)) throw new BusinessException(ErrorCode.INVALID_INPUT, "방장만 시작할 수 있습니다");
        int n = players.size();
        if (n < 5) throw new BusinessException(ErrorCode.INVALID_INPUT, "최소 5명이 필요합니다");

        // 범위 안에서 랜덤으로 각 직업 인원 결정
        int mafia = Math.max(1, randRange(mafiaMin, mafiaMax)); // 마피아는 최소 1 보장
        int psycho = randRange(psychoMin, psychoMax);
        int observer = randRange(observerMin, observerMax);
        int blocker = randRange(blockerMin, blockerMax);
        int attention, thief;
        if (neutralGrouped) {
            // 중립 통합: 총 인원만 뽑고, 각 자리를 관종/도적꾼 중 랜덤으로 채움
            int neutralTotal = randRange(neutralMin, neutralMax);
            attention = 0; thief = 0;
            for (int k = 0; k < neutralTotal; k++) {
                if (ThreadLocalRandom.current().nextBoolean()) attention++; else thief++;
            }
        } else {
            attention = randRange(attentionMin, attentionMax);
            thief = randRange(thiefMin, thiefMax);
        }
        // 인원 초과 시 특수직업부터 줄임(마피아는 1까지만 감축). +2 = 경찰·의사 고정
        while (mafia + psycho + observer + blocker + attention + thief + 2 > n) {
            if (thief > 0) thief--;
            else if (attention > 0) attention--;
            else if (blocker > 0) blocker--;
            else if (observer > 0) observer--;
            else if (psycho > 0) psycho--;
            else if (mafia > 1) mafia--;
            else break;
        }
        if (mafia + psycho + observer + blocker + attention + thief + 2 > n)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "인원이 부족합니다");

        // 마피아 총원(mafia) 안에서 특수 마피아 배정(나머지는 일반 마피아)
        int cop = Math.min(randRange(mafiaCopMin, mafiaCopMax), mafia);
        int shadow = Math.min(randRange(mafiaShadowMin, mafiaShadowMax), mafia - cop);
        int mObs = Math.min(randRange(mafiaObserverMin, mafiaObserverMax), mafia - cop - shadow);
        int mBlk = Math.min(randRange(mafiaBlockerMin, mafiaBlockerMax), mafia - cop - shadow - mObs);
        int plainMafia = mafia - cop - shadow - mObs - mBlk;

        List<Role> roles = new ArrayList<>();
        for (int i = 0; i < plainMafia; i++) roles.add(Role.MAFIA);
        for (int i = 0; i < cop; i++) roles.add(Role.MAFIA_COP);
        for (int i = 0; i < shadow; i++) roles.add(Role.MAFIA_SHADOW);
        for (int i = 0; i < mObs; i++) roles.add(Role.MAFIA_OBSERVER);
        for (int i = 0; i < mBlk; i++) roles.add(Role.MAFIA_BLOCKER);
        roles.add(Role.POLICE);
        roles.add(Role.DOCTOR);
        for (int i = 0; i < psycho; i++) roles.add(Role.PSYCHO);
        for (int i = 0; i < observer; i++) roles.add(Role.OBSERVER);
        for (int i = 0; i < blocker; i++) roles.add(Role.BLOCKER);
        for (int i = 0; i < attention; i++) roles.add(Role.ATTENTION);
        for (int i = 0; i < thief; i++) roles.add(Role.THIEF);
        while (roles.size() < n) roles.add(Role.CITIZEN);
        Collections.shuffle(roles);

        psychoFakeRoles.clear();
        for (int i = 0; i < n; i++) {
            players.get(i).role = roles.get(i);
            players.get(i).alive = true;
            if (roles.get(i) == Role.PSYCHO) {
                // 정신병자에게 보일 가짜 직업(경찰/의사/관찰자/봉쇄자 중 랜덤) — 능력은 효과 없음
                Role[] fakes = { Role.POLICE, Role.DOCTOR, Role.OBSERVER, Role.BLOCKER };
                psychoFakeRoles.put(i, fakes[ThreadLocalRandom.current().nextInt(fakes.length)]);
            }
        }

        round = 1;
        copLog.clear();
        psychoCopLogs.clear();
        myLogs.clear();
        history.clear();
        history.add("🎬 게임 시작 · " + n + "명 (마피아 " + mafia + "명"
                + (cop > 0 ? "(경찰마피아 " + cop + ")" : "") + (shadow > 0 ? "(그림자마피아 " + shadow + ")" : "")
                + (psycho > 0 ? " · 정신병자 " + psycho : "") + (attention > 0 ? " · 관종 " + attention : "")
                + (thief > 0 ? " · 도적꾼 " + thief : "") + ")");
        prepareNight();
        startPhase(Phase.NIGHT);
        return me(clientId);
    }

    public synchronized JobMafiaStateResponse nightAction(String clientId, int target) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.NIGHT) throw new BusinessException(ErrorCode.INVALID_INPUT, "지금은 밤이 아닙니다");
        if (!me.alive) throw new BusinessException(ErrorCode.INVALID_INPUT, "사망한 플레이어입니다");
        int seat = seatOf(me);
        Role acting = effectiveRole(me);
        int t = to0(target);
        if (!selectableSeats(seat, acting).contains(t))
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지목할 수 없는 대상입니다");

        // 밤엔 '지목'만 기록한다(각자 한 명). 실제 판정(살해·치료·조사)은 아침에 한 번에.
        // → 클릭마다 결과가 뜨지 않고, 조사 결과는 아침에 딱 한 줄만 남는다.
        nightTargetBySeat.put(seat, t);
        nightActed.add(seat);
        maybeAdvanceNight();
        return me(clientId);
    }

    /** 능력마피아(경찰·관찰자·봉쇄자마피아): 이번 밤 모드(능력/살해) 선택. 모드가 바뀌면 지목 초기화. */
    public synchronized JobMafiaStateResponse setCopMafiaMode(String clientId, boolean ability) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.NIGHT) throw new BusinessException(ErrorCode.INVALID_INPUT, "지금은 밤이 아닙니다");
        if (!me.alive || !isAbilityMafia(me.role))
            throw new BusinessException(ErrorCode.INVALID_INPUT, "능력마피아만 사용할 수 있습니다");
        int seat = seatOf(me);
        boolean prev = mafiaAbilityMode.getOrDefault(seat, false);
        if (prev != ability) {               // 모드 변경 시 대상 초기화(선택 대상군이 달라짐)
            mafiaAbilityMode.put(seat, ability);
            nightTargetBySeat.remove(seat);
            nightActed.remove(seat);
        }
        return me(clientId);
    }

    public synchronized JobMafiaStateResponse vote(String clientId, int target) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.VOTE) throw new BusinessException(ErrorCode.INVALID_INPUT, "지금은 투표 시간이 아닙니다");
        if (!me.alive) throw new BusinessException(ErrorCode.INVALID_INPUT, "사망한 플레이어입니다");
        int t = to0(target);
        if (t != -1 && !aliveSeats().contains(t))
            throw new BusinessException(ErrorCode.INVALID_INPUT, "투표할 수 없는 대상입니다");
        if (t == seatOf(me)) throw new BusinessException(ErrorCode.INVALID_INPUT, "자신에게 투표할 수 없습니다");
        votes.put(seatOf(me), t);
        if (votes.size() >= aliveSeats().size()) advance();
        return me(clientId);
    }

    /** 사형/생존 투표(최후변론 후). 재판 당사자는 투표 불가. execute=true(사형)/false(생존). */
    public synchronized JobMafiaStateResponse finalVote(String clientId, boolean execute) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.FINAL_VOTE) throw new BusinessException(ErrorCode.INVALID_INPUT, "지금은 사형/생존 투표 시간이 아닙니다");
        if (!me.alive) throw new BusinessException(ErrorCode.INVALID_INPUT, "사망한 플레이어입니다");
        int seat = seatOf(me);
        if (seat == accusedSeat) throw new BusinessException(ErrorCode.INVALID_INPUT, "재판 당사자는 투표할 수 없습니다");
        finalVotes.put(seat, execute);
        long eligible = aliveSeats().stream().filter(s -> s != accusedSeat).count();
        if (finalVotes.size() >= eligible) advance();
        return me(clientId);
    }

    public synchronized JobMafiaStateResponse resetGame() {
        reset();
        return JobMafiaStateResponse.notStarted(System.currentTimeMillis());
    }

    public synchronized JobMafiaStateResponse me(String clientId) {
        lastActiveMs = System.currentTimeMillis();
        tick();
        return buildResponse(clientId);
    }

    // ---- RoomGame ----
    @Override public synchronized String roomStatus() {
        if (phase == null || phase == Phase.LOBBY) return "WAITING";
        return phase == Phase.ENDED ? "ENDED" : "PLAYING";
    }
    @Override public synchronized int playerCount() {
        return (int) players.stream().filter(p -> !leftClients.contains(p.clientId)).count();
    }
    @Override public synchronized void leave(String clientId) {
        Integer seat = clientSeats.get(clientId);
        if (seat == null) return;
        lastActiveMs = System.currentTimeMillis();
        if (phase == null || phase == Phase.LOBBY) {
            players.remove((int) seat);
            clientSeats.clear();
            for (int i = 0; i < players.size(); i++) clientSeats.put(players.get(i).clientId, i);
            if (clientId.equals(hostClientId)) hostClientId = players.isEmpty() ? null : players.get(0).clientId;
        } else {
            leftClients.add(clientId);
        }
    }
    @Override public synchronized String hostLabel() { return players.isEmpty() ? "" : players.get(0).nick; }
    @Override public synchronized boolean isEnded() { return phase == Phase.ENDED; }
    @Override public synchronized long lastActiveMs() { return lastActiveMs; }

    // =================== 타이머/진행 ===================

    private void tick() {
        long now = System.currentTimeMillis();
        int guard = 0;
        while (phase != null && phase != Phase.LOBBY && phase != Phase.ENDED
                && phaseEndsAt > 0 && now >= phaseEndsAt && guard++ < 30) {
            advance();
        }
    }

    private void maybeAdvanceNight() {
        if (nightActed.containsAll(aliveSeats())) advance();
    }

    private void advance() {
        switch (phase) {
            case NIGHT -> { resolveNight(); if (!checkWin()) startPhase(Phase.MORNING); }
            case MORNING -> startPhase(Phase.DISCUSS);
            case DISCUSS -> { votes.clear(); startPhase(Phase.VOTE); }
            case VOTE -> {
                resolveNomination();                      // 최다 득표자를 재판대에 올림(아직 죽이지 않음)
                if (accusedSeat >= 0) { finalVotes.clear(); startPhase(Phase.DEFENSE); }
                else { executedSeat = -1; startPhase(Phase.EXECUTE); } // 지목 없음 → 처형 없음
            }
            case DEFENSE -> { finalVotes.clear(); startPhase(Phase.FINAL_VOTE); }
            case FINAL_VOTE -> {
                resolveFinalVote();                       // 사형/생존 집계 → 실제 처형 여부 결정
                if (executedSeat >= 0 && players.get(executedSeat).role == Role.ATTENTION) {
                    winner = "NEUTRAL"; phase = Phase.ENDED; phaseEndsAt = 0; // 관종 처형 → 관종 승
                    history.add("🏁 관종 단독 승리!");
                } else if (!checkWin()) {
                    startPhase(Phase.EXECUTE);
                }
            }
            case EXECUTE -> { round++; prepareNight(); startPhase(Phase.NIGHT); }
            default -> { }
        }
    }

    private void startPhase(Phase p) {
        phase = p;
        long now = System.currentTimeMillis();
        phaseEndsAt = switch (p) {
            case NIGHT -> now + nightMs;
            case MORNING -> now + MORNING_MS;
            case DISCUSS -> now + discussMs;
            case VOTE -> now + voteMs;
            case DEFENSE -> now + defenseMs;
            case FINAL_VOTE -> now + finalVoteMs;
            case EXECUTE -> now + EXECUTE_MS;
            default -> 0;
        };
    }

    private void prepareNight() {
        nightTargetBySeat.clear();
        nightActed.clear();
        mafiaAbilityMode.clear(); // 매 밤 능력마피아 모드 초기화(기본 살해)
        blockedSeats.clear();
    }

    /** 아침 판정: 조사·치료·살해를 이번 밤 지목으로 한 번에 처리. */
    private void resolveNight() {
        nightDeadSeat = -1;

        // 0) 봉쇄 먼저: 봉쇄자(시민)·봉쇄자마피아(능력모드)의 대상은 이번 밤 능력 무효
        blockedSeats.clear();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            if (!p.alive) continue;
            boolean cityBlocker = p.role == Role.BLOCKER;
            boolean mafBlocker = p.role == Role.MAFIA_BLOCKER && mafiaAbilityMode.getOrDefault(i, false);
            if (!cityBlocker && !mafBlocker) continue;
            Integer t = nightTargetBySeat.get(i);
            if (t != null && t >= 0 && t < players.size()) blockedSeats.add(t);
        }

        int doctorTarget = roleTarget(Role.DOCTOR); // 의사 보호 대상(없으면 -1)
        int docSeat0 = aliveSeatOfRole(Role.DOCTOR);
        if (docSeat0 >= 0 && blockedSeats.contains(docSeat0)) doctorTarget = -1; // 의사 봉쇄 시 보호 무효

        // 1) 살해 의도 수집(봉쇄된 마피아는 제외). target -> 은폐(그림자가 노림)
        Map<Integer, Boolean> killIntent = new LinkedHashMap<>();
        Set<Integer> targetedSet = new HashSet<>();
        if (!abilityIndependentKill) {
            int mafiaTarget = mafiaPlurality(); // isMafiaKilling이 봉쇄 반영
            if (mafiaTarget >= 0) {
                boolean shadowKilling = false;
                for (int i = 0; i < players.size(); i++)
                    if (players.get(i).alive && players.get(i).role == Role.MAFIA_SHADOW && !blockedSeats.contains(i)) shadowKilling = true;
                targetedSet.add(mafiaTarget); killIntent.put(mafiaTarget, shadowKilling);
            }
        } else {
            int plain = plainMafiaPlurality();
            if (plain >= 0) { targetedSet.add(plain); killIntent.merge(plain, false, (a, b) -> a); }
            for (int i = 0; i < players.size(); i++) {
                Player mp = players.get(i);
                if (!mp.alive || blockedSeats.contains(i)) continue;
                boolean abilityKill = isAbilityMafia(mp.role) && !mafiaAbilityMode.getOrDefault(i, false);
                boolean shadow = mp.role == Role.MAFIA_SHADOW;
                if (!abilityKill && !shadow) continue;
                Integer t = nightTargetBySeat.get(i);
                if (t == null || t < 0) continue;
                targetedSet.add(t);
                killIntent.merge(t, shadow, (prev, now) -> prev || now);
            }
        }

        // 2) 적용: 의사 보호 대상은 생존, 나머지 처치
        List<Integer> deaths = new ArrayList<>();
        for (var e : killIntent.entrySet()) {
            int t = e.getKey();
            if (t == doctorTarget) continue;
            if (t < 0 || t >= players.size() || !players.get(t).alive) continue;
            players.get(t).alive = false;
            deaths.add(t);
            if (e.getValue()) concealedSeats.add(t);
        }
        Set<Integer> deadSet = new HashSet<>(deaths);
        nightDeadSeat = deaths.isEmpty() ? -1 : deaths.get(0);

        if (deaths.isEmpty()) {
            nightMessage = "평화로운 밤이었습니다. 아무도 죽지 않았습니다.";
        } else {
            List<String> names = new ArrayList<>(); boolean anyConceal = false;
            for (int t : deaths) { names.add(players.get(t).nick); if (concealedSeats.contains(t)) anyConceal = true; }
            nightMessage = String.join(", ", names) + "님이 밤 사이 사망했습니다."
                    + (anyConceal ? " (일부 정체가 밝혀지지 않았습니다)" : "");
        }
        history.add(round + "일차 🌙 " + nightMessage);

        // 3) 의사 개인 기록
        int docSeat = aliveSeatOfRole(Role.DOCTOR);
        if (docSeat >= 0 && doctorTarget >= 0) {
            boolean saved = targetedSet.contains(doctorTarget);
            myLog(docSeat).add(round + "일차 💉 보호: " + players.get(doctorTarget).nick
                    + (saved ? " ⭕ 성공 (마피아 공격을 막음!)" : " ❌ (마피아가 노린 대상이 아니었음)"));
        }

        // 4) 마피아 개인 기록(능력/살해). 봉쇄 시 실패
        for (int i = 0; i < players.size(); i++) {
            Player mp = players.get(i);
            if (!isMafia(mp.role) || !mp.alive) continue;
            Integer t = nightTargetBySeat.get(i);
            if (t == null || t < 0) continue;
            if (blockedSeats.contains(i)) { myLog(i).add(round + "일차 🚫 봉쇄당해 이번 밤 아무것도 못 했습니다."); continue; }
            boolean ability = isAbilityMafia(mp.role) && mafiaAbilityMode.getOrDefault(i, false);
            if (ability && mp.role == Role.MAFIA_COP) myLog(i).add(round + "일차 🔎 조사: " + players.get(t).nick + " → " + realScan(t));
            else if (ability && mp.role == Role.MAFIA_OBSERVER) myLog(i).add(round + "일차 👁 관찰: " + players.get(t).nick + " → " + observeInfo(t));
            else if (ability && mp.role == Role.MAFIA_BLOCKER) myLog(i).add(round + "일차 🚫 봉쇄: " + players.get(t).nick + "의 능력을 막았다");
            else { String res = deadSet.contains(t) ? players.get(t).nick + " 처치 성공" : "실패(보호/생존)"; myLog(i).add(round + "일차 🔪 지목: " + players.get(t).nick + " · 결과: " + res); }
        }

        // 5) 관찰자(시민) — 대상의 밤 지목만 확인(행동 종류는 모름). 봉쇄 시 실패
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).role != Role.OBSERVER || !players.get(i).alive) continue;
            if (blockedSeats.contains(i)) { myLog(i).add(round + "일차 🚫 누군가 관찰을 방해했습니다."); continue; }
            Integer t = nightTargetBySeat.get(i);
            if (t == null || t < 0) continue;
            myLog(i).add(round + "일차 👁 관찰: " + players.get(t).nick + " → " + observeInfo(t));
        }

        // 6) 봉쇄자(시민) 기록
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).role != Role.BLOCKER || !players.get(i).alive) continue;
            Integer t = nightTargetBySeat.get(i);
            if (t == null || t < 0) continue;
            myLog(i).add(round + "일차 🚫 봉쇄: " + players.get(t).nick + "의 능력을 막았다");
        }

        // 경찰 조사(진짜: 직업 후보 2개 중 하나가 진짜) — 아침에 결과 1줄. 봉쇄 시 실패
        int copSeat = aliveSeatOfRole(Role.POLICE);
        if (copSeat >= 0 && blockedSeats.contains(copSeat)) {
            myLog(copSeat).add(round + "일차 🚫 누군가 조사를 방해했습니다.");
        } else if (copSeat >= 0) {
            Integer t = nightTargetBySeat.get(copSeat);
            if (t != null && t >= 0) {
                copLog.add(round + "일차: " + players.get(t).nick + " → " + realScan(t));
                myLog(copSeat).add(round + "일차 🔎 조사: " + players.get(t).nick + " → " + copLog.get(copLog.size() - 1).split(" → ")[1]);
            }
        }
        // 정신병자(가짜 경찰) 가짜 조사 — 직업 2개 동등확률(우연히 진짜가 섞일 수도)
        for (var e : psychoFakeRoles.entrySet()) {
            int ps = e.getKey();
            if (e.getValue() != Role.POLICE || !players.get(ps).alive || blockedSeats.contains(ps)) continue;
            Integer t = nightTargetBySeat.get(ps);
            if (t != null && t >= 0) {
                psychoCopLogBySeat(ps).add(round + "일차: " + players.get(t).nick + " → " + fakeScan());
                myLog(ps).add(round + "일차 🔎 조사: " + players.get(t).nick + " → " + psychoCopLogBySeat(ps).get(psychoCopLogBySeat(ps).size() - 1).split(" → ")[1]);
            }
        }
        // 정신병자(가짜 관찰자) 가짜 관찰 — 무작위 결과(효과 없음)
        for (var e : psychoFakeRoles.entrySet()) {
            int ps = e.getKey();
            if (e.getValue() != Role.OBSERVER || !players.get(ps).alive || blockedSeats.contains(ps)) continue;
            Integer t = nightTargetBySeat.get(ps);
            if (t != null && t >= 0) myLog(ps).add(round + "일차 👁 관찰: " + players.get(t).nick + " → " + fakeObserveInfo());
        }
        // 정신병자(가짜 봉쇄자) — 본인은 막은 줄 알지만 실제론 아무것도 안 막힘
        for (var e : psychoFakeRoles.entrySet()) {
            int ps = e.getKey();
            if (e.getValue() != Role.BLOCKER || !players.get(ps).alive || blockedSeats.contains(ps)) continue;
            Integer t = nightTargetBySeat.get(ps);
            if (t != null && t >= 0) myLog(ps).add(round + "일차 🚫 봉쇄: " + players.get(t).nick + "의 능력을 막았다");
        }

        // 도적꾼: 대상의 직업을 훔쳐온다. 이 밤의 다른 능력은 위에서 이미 처리됐으므로 결과는 유지된다.
        // (예: 피해자가 의사로 A를 살렸다면 그 치료는 반영되고, 다음 아침부터 피해자는 무직 시민이 된다.)
        for (int i = 0; i < players.size(); i++) {
            Player thief = players.get(i);
            if (thief.role != Role.THIEF || !thief.alive) continue;
            if (blockedSeats.contains(i)) { myLog(i).add(round + "일차 🚫 봉쇄당해 강탈에 실패했습니다."); continue; }
            Integer t = nightTargetBySeat.get(i);
            if (t == null || t < 0 || t >= players.size() || t == i) continue;
            Player victim = players.get(t);
            Role stolen = victim.role;
            if (stolen == null) continue;
            thief.role = stolen;
            victim.role = Role.CITIZEN;
            // 정신병자 상태 이관(훔친 직업이 정신병자면 도적꾼이 가짜직업을 물려받는다)
            psychoFakeRoles.remove(i);
            if (stolen == Role.PSYCHO) {
                psychoFakeRoles.put(i, psychoFakeRoles.getOrDefault(t, Role.POLICE));
            }
            psychoFakeRoles.remove(t);
            myLog(i).add(round + "일차 🕵️ " + victim.nick + "의 직업(" + jobLabel(stolen) + ")을 훔쳤다!");
        }
    }

    private String jobLabel(Role r) {
        return switch (r) {
            case CITIZEN -> "시민";
            case POLICE -> "경찰";
            case DOCTOR -> "의사";
            case PSYCHO -> "정신병자";
            case OBSERVER -> "관찰자";
            case BLOCKER -> "봉쇄자";
            case MAFIA -> "마피아";
            case MAFIA_COP -> "경찰마피아";
            case MAFIA_SHADOW -> "그림자마피아";
            case MAFIA_OBSERVER -> "관찰자마피아";
            case MAFIA_BLOCKER -> "봉쇄자마피아";
            case ATTENTION -> "관종";
            case THIEF -> "도적꾼";
        };
    }

    /** 이 게임에 존재하는 직업 라벨을 팀(시민/마피아/중립)별로 모은다(중복 제거). */
    private Map<String, List<String>> presentLabelsByTeam() {
        Map<String, List<String>> m = new HashMap<>();
        for (Player p : players) {
            if (p.role == null) continue;
            String lbl = jobLabel(p.role);
            List<String> list = m.computeIfAbsent(teamOf(p.role), k -> new ArrayList<>());
            if (!list.contains(lbl)) list.add(lbl);
        }
        return m;
    }

    private static String pickRandom(List<String> xs) { return xs.get(ThreadLocalRandom.current().nextInt(xs.size())); }

    /**
     * 진짜 경찰: 진짜 직업 + '다른 팀'의 미끼 직업 1개. 순서 무작위.
     * 두 후보는 항상 서로 다른 팀(시민/마피아/중립)이라, 팀까지 확정되지 않고 2팀 중 하나로만 좁혀진다.
     */
    private String realScan(int targetSeat) {
        Role truthRole = players.get(targetSeat).role;
        String truth = jobLabel(truthRole);
        String truthTeam = teamOf(truthRole);
        Map<String, List<String>> byTeam = presentLabelsByTeam();
        List<String> otherTeams = new ArrayList<>(byTeam.keySet());
        otherTeams.remove(truthTeam);
        String decoy = otherTeams.isEmpty() ? truth : pickRandom(byTeam.get(pickRandom(otherTeams)));
        List<String> two = new ArrayList<>(List.of(truth, decoy));
        Collections.shuffle(two);
        return two.get(0) + " | " + two.get(1);
    }

    /** 정신병자 가짜 경찰: 서로 다른 두 팀에서 각각 1개(진짜 경찰과 형식을 맞춰 위장 유지). */
    private String fakeScan() {
        Map<String, List<String>> byTeam = presentLabelsByTeam();
        List<String> teams = new ArrayList<>(byTeam.keySet());
        Collections.shuffle(teams);
        String a, b;
        if (teams.size() >= 2) {
            a = pickRandom(byTeam.get(teams.get(0)));
            b = pickRandom(byTeam.get(teams.get(1)));
        } else {
            List<String> only = teams.isEmpty() ? List.of("시민") : byTeam.get(teams.get(0));
            a = only.get(0);
            b = only.size() > 1 ? only.get(1) : a;
        }
        List<String> two = new ArrayList<>(List.of(a, b));
        Collections.shuffle(two);
        return two.get(0) + " | " + two.get(1);
    }

    private int aliveSeatOfRole(Role r) {
        for (int i = 0; i < players.size(); i++)
            if (players.get(i).alive && players.get(i).role == r) return i;
        return -1;
    }

    /** 해당 직업(생존)의 이번 밤 지목 대상(0-based), 없으면 -1. */
    private int roleTarget(Role r) {
        int s = aliveSeatOfRole(r);
        if (s < 0) return -1;
        Integer t = nightTargetBySeat.get(s);
        return t == null ? -1 : t;
    }

    /** 이번 밤 살해에 가담하는 마피아 좌석인가(능력마피아가 능력모드면 제외, 봉쇄당하면 제외). */
    private boolean isMafiaKilling(int seat) {
        Role r = players.get(seat).role;
        if (!isMafia(r)) return false;
        if (blockedSeats.contains(seat)) return false;
        return !(isAbilityMafia(r) && mafiaAbilityMode.getOrDefault(seat, false));
    }

    /** 살해에 가담하는 마피아들의 지목 다수결(동수는 낮은 좌석). 없으면 -1. */
    /** 일반 마피아(MAFIA)만의 다수결 표적 — 독립 킬 모드에서 능력마피아를 제외한 공유 킬. */
    private int plainMafiaPlurality() {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).alive && players.get(i).role == Role.MAFIA && !blockedSeats.contains(i)) {
                Integer t = nightTargetBySeat.get(i);
                if (t != null && t >= 0) counts.merge(t, 1, Integer::sum);
            }
        }
        return pluralityWinner(counts);
    }

    /** 정신병자(가짜 관찰자)용 가짜 관찰 결과 — 진짜와 형식은 같지만 무작위. */
    private String fakeObserveInfo() {
        List<Integer> alive = aliveSeats();
        if (!alive.isEmpty() && ThreadLocalRandom.current().nextInt(4) != 0) {
            int r = alive.get(ThreadLocalRandom.current().nextInt(alive.size()));
            return players.get(r).nick + "을(를) 지목함 (무언가 행동)";
        }
        return "밤에 아무 행동도 하지 않음";
    }

    /** 관찰 결과: 대상이 밤에 누구를 지목했는지만(행동 종류는 모름).
     *  봉쇄자가 먼저 행위하므로, 봉쇄된 대상은 행동 자체를 못 해 "행동 없음"으로 보인다. */
    private String observeInfo(int targetSeat) {
        if (blockedSeats.contains(targetSeat)) return "밤에 아무 행동도 하지 못함";
        Integer p = nightTargetBySeat.get(targetSeat);
        return (p != null && p >= 0 && p < players.size())
                ? players.get(p).nick + "을(를) 지목함 (무언가 행동)"
                : "밤에 아무 행동도 하지 않음";
    }

    private static int pluralityWinner(Map<Integer, Integer> counts) {
        int best = -1, bestCount = 0;
        for (var e : counts.entrySet()) {
            if (e.getValue() > bestCount || (e.getValue() == bestCount && e.getKey() < best)) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    // ── 테스트 헬퍼 ─────────────────────────────────────
    void tSetup(java.util.List<Role> roles) {
        players.clear(); clientSeats.clear();
        for (int i = 0; i < roles.size(); i++) { Player p = new Player("c" + i, "P" + i); p.role = roles.get(i); players.add(p); }
        round = 1; concealedSeats.clear(); nightTargetBySeat.clear(); mafiaAbilityMode.clear();
    }
    void tTarget(int seat, int target) { nightTargetBySeat.put(seat, target); }
    void tMode(boolean b) { abilityIndependentKill = b; }
    void tAbilityMode(int seat, boolean on) { mafiaAbilityMode.put(seat, on); }
    void tResolve() { resolveNight(); }
    boolean tAlive(int seat) { return players.get(seat).alive; }
    boolean tConcealed(int seat) { return concealedSeats.contains(seat); }
    List<String> tMyLog(int seat) { return myLog(seat); }
    String tRole(int seat) { return jobLabel(players.get(seat).role); }
    void tFakeRole(int seat, Role r) { psychoFakeRoles.put(seat, r); }

    private int mafiaPlurality() {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).alive && isMafiaKilling(i)) {
                Integer t = nightTargetBySeat.get(i);
                if (t != null && t >= 0) counts.merge(t, 1, Integer::sum);
            }
        }
        int best = -1, bestCount = 0;
        for (var e : counts.entrySet()) {
            if (e.getValue() > bestCount || (e.getValue() == bestCount && e.getKey() < best)) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    /** 낮 투표 집계 → 최다 득표자를 재판대에 올린다(아직 죽이지 않음). 동표·기권이면 지목 없음. */
    private void resolveNomination() {
        accusedSeat = -1; executedSeat = -1;
        Map<Integer, Integer> tally = new HashMap<>();
        for (int t : votes.values()) if (t != -1) tally.merge(t, 1, Integer::sum);
        for (var e : votes.entrySet())
            myLog(e.getKey()).add(round + "일차 🗳️ 투표: " + (e.getValue() < 0 ? "기권" : players.get(e.getValue()).nick));
        if (!tally.isEmpty()) {
            List<Map.Entry<Integer, Integer>> es = new ArrayList<>(tally.entrySet());
            es.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder sb = new StringBuilder();
            for (var en : es) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(players.get(en.getKey()).nick).append(" ").append(en.getValue()).append("표");
            }
            history.add(round + "일차 🗳️ 집계: " + sb);
        }
        int max = 0, top = -1; boolean tie = false;
        for (var e : tally.entrySet()) {
            if (e.getValue() > max) { max = e.getValue(); top = e.getKey(); tie = false; }
            else if (e.getValue() == max) tie = true;
        }
        if (top >= 0 && !tie && max > 0) {
            accusedSeat = top;
            history.add(round + "일차 ⚖️ " + players.get(top).nick + "님이 최다 득표 — 최후변론 후 사형/생존 투표");
        } else {
            history.add(round + "일차 ☀️ 지목 없음 (동표 또는 기권) — 처형 없음");
        }
    }

    /** 사형/생존 투표 집계 → 사형 표가 더 많으면 처형. 재판대 본인은 투표 못 함. */
    private void resolveFinalVote() {
        executedSeat = -1;
        int kill = 0, spare = 0;
        for (boolean v : finalVotes.values()) { if (v) kill++; else spare++; }
        history.add(round + "일차 ⚖️ 사형투표: 사형 " + kill + " · 생존 " + spare);
        if (accusedSeat >= 0 && kill > spare && players.get(accusedSeat).alive) {
            players.get(accusedSeat).alive = false;
            executedSeat = accusedSeat;
            history.add(round + "일차 ☀️ " + players.get(accusedSeat).nick + "님 처형 (정체: " + jobLabel(players.get(accusedSeat).role) + ")");
        } else {
            history.add(round + "일차 ☀️ " + (accusedSeat >= 0 ? players.get(accusedSeat).nick + "님 생존 (사형 부결)" : "처형 없음"));
        }
        accusedSeat = -1;
    }

    private boolean checkWin() {
        long mafiaAlive = players.stream().filter(p -> p.alive && isMafia(p.role)).count();
        long nonMafiaAlive = players.stream().filter(p -> p.alive && p.role != null && !isMafia(p.role)).count();
        if (mafiaAlive == 0) { winner = "CITIZEN"; phase = Phase.ENDED; phaseEndsAt = 0; history.add("🏁 시민팀 승리!"); return true; }
        if (mafiaAlive >= nonMafiaAlive) { winner = "MAFIA"; phase = Phase.ENDED; phaseEndsAt = 0; history.add("🏁 마피아팀 승리!"); return true; }
        return false;
    }

    // =================== 응답 빌드 ===================

    private JobMafiaStateResponse buildResponse(String clientId) {
        long now = System.currentTimeMillis();
        if (phase == null) return JobMafiaStateResponse.notStarted(now);

        boolean ended = phase == Phase.ENDED;
        Integer mySeatIdx = clientSeats.get(clientId);
        Player me = mySeatIdx == null ? null : players.get(mySeatIdx);
        boolean joined = me != null;

        List<PlayerView> board = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            String shownRole = null;
            // 그림자마피아에게 은폐된 좌석은 게임 종료 전까진 정체를 숨긴다(종료 시 전체 공개).
            if (p.role != null && (ended || (revealOnDeath && !p.alive && !concealedSeats.contains(i))))
                shownRole = p.role.name(); // 실제 정체
            board.add(new PlayerView(i + 1, p.nick, p.alive, shownRole));
        }

        String actionKind = "NONE";
        List<Integer> selectable = List.of();
        int myTarget = -1;
        List<Integer> fellow = List.of();
        List<String> myCopLog = List.of();
        List<VoteView> mafiaPickTally = List.of();

        Role display = joined && me.role != null ? effectiveRole(me) : null; // 정신병자는 가짜 직업

        if (joined && me.alive) {
            if (phase == Phase.NIGHT && display != null) {
                actionKind = switch (display) {
                    case MAFIA, MAFIA_SHADOW -> "MAFIA_KILL";  // 그림자마피아도 UI는 살해(은폐는 자동)
                    case MAFIA_COP -> "MAFIA_COP";             // 능력마피아: 능력/살해 택1
                    case MAFIA_OBSERVER -> "MAFIA_OBSERVER";
                    case MAFIA_BLOCKER -> "MAFIA_BLOCKER";
                    case POLICE -> "POLICE_CHECK";
                    case DOCTOR -> "DOCTOR_SAVE";
                    case OBSERVER -> "OBSERVER_WATCH";
                    case BLOCKER -> "BLOCKER_BLOCK";
                    case THIEF -> "THIEF_STEAL";
                    default -> "CITIZEN_WATCH"; // 시민/관종(위장 지목)
                };
                selectable = selectableSeats(seatOf(me), display).stream().map(s -> s + 1).toList();
                Integer mt = nightTargetBySeat.get(seatOf(me));
                myTarget = mt == null || mt < 0 ? -1 : mt + 1;
            } else if (phase == Phase.VOTE) {
                actionKind = "VOTE";
                Integer v = votes.get(seatOf(me));
                myTarget = v == null || v == -1 ? -1 : v + 1;
                selectable = aliveSeats().stream().filter(s -> s != seatOf(me)).map(s -> s + 1).toList();
            }
        }

        if (joined && isMafia(me.role)) {
            fellow = new ArrayList<>();
            for (int i = 0; i < players.size(); i++)
                if (isMafia(players.get(i).role)) fellow.add(i + 1);
            if (phase == Phase.NIGHT) {
                Map<Integer, Integer> counts = new LinkedHashMap<>();
                for (int i = 0; i < players.size(); i++) {
                    if (players.get(i).alive && isMafiaKilling(i)) {
                        Integer t = nightTargetBySeat.get(i);
                        if (t != null && t >= 0) counts.merge(t, 1, Integer::sum);
                    }
                }
                List<VoteView> mp = new ArrayList<>();
                counts.forEach((k, v) -> mp.add(new VoteView(k + 1, v)));
                mafiaPickTally = mp;
            }
        }
        // 경찰 조사 기록은 '밤엔 숨기고 아침부터' 공개(진짜 경찰=정확, 정신병자=가짜).
        if (joined && phase != Phase.NIGHT) {
            if (me.role == Role.POLICE) myCopLog = List.copyOf(copLog);
            else if (me.role == Role.PSYCHO && psychoFakeRoles.get(mySeatIdx) == Role.POLICE)
                myCopLog = List.copyOf(psychoCopLogBySeat(mySeatIdx));
        }

        List<VoteView> tally = new ArrayList<>();
        if (phase == Phase.VOTE || phase == Phase.EXECUTE) {
            Map<Integer, Integer> counts = new LinkedHashMap<>();
            for (int t : votes.values()) if (t != -1) counts.merge(t, 1, Integer::sum);
            counts.forEach((k, v) -> tally.add(new VoteView(k + 1, v)));
        }

        return new JobMafiaStateResponse(
                phase.name(),
                round,
                phaseEndsAt,
                now,
                clientId.equals(hostClientId),
                joined,
                joined ? mySeatIdx + 1 : 0,
                joined ? me.nick : null,
                display != null ? display.name() : null,
                display != null ? teamOf(display) : null,
                joined && me.alive,
                board,
                actionKind,
                joined && isAbilityMafia(me.role) && mafiaAbilityMode.getOrDefault(mySeatIdx, false),
                selectable,
                myTarget,
                fellow,
                myCopLog,
                mafiaPickTally,
                (phase == Phase.MORNING || phase == Phase.DISCUSS || phase == Phase.VOTE || ended) ? nightMessage : null,
                (phase == Phase.MORNING) ? seat1(nightDeadSeat) : -1,
                (phase == Phase.EXECUTE || ended) ? seat1(executedSeat) : -1,
                tally,
                (phase == Phase.DEFENSE || phase == Phase.FINAL_VOTE) ? seat1(accusedSeat) : -1,
                (int) finalVotes.values().stream().filter(Boolean::booleanValue).count(),
                (int) finalVotes.values().stream().filter(v -> !v).count(),
                joined && finalVotes.containsKey(mySeatIdx) ? (finalVotes.get(mySeatIdx) ? 1 : 0) : -1,
                ended ? winner : null,
                (int) players.stream().filter(p -> p.alive).count(),
                players.size(),
                joined && myLogs.containsKey(mySeatIdx) ? List.copyOf(myLogs.get(mySeatIdx)) : List.of(),
                List.copyOf(history)
        );
    }

    // =================== 유틸 ===================

    /** 정신병자는 가짜 직업으로 취급(본인 화면·행동). 그 외는 실제 직업. */
    private Role effectiveRole(Player p) {
        return p.role == Role.PSYCHO ? psychoFakeRoles.getOrDefault(seatOf(p), Role.POLICE) : p.role;
    }

    private String teamOf(Role r) {
        return switch (r) {
            case MAFIA, MAFIA_COP, MAFIA_SHADOW, MAFIA_OBSERVER, MAFIA_BLOCKER -> "MAFIA";
            case ATTENTION, THIEF -> "NEUTRAL";
            default -> "CITIZEN";
        };
    }

    /** effective role 기준, 이번 밤 지목 가능한 좌석(0-based). */
    private List<Integer> selectableSeats(int mySeat, Role acting) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            if (!players.get(i).alive) continue;
            switch (acting) {
                case MAFIA, MAFIA_SHADOW -> { if (!isMafia(players.get(i).role)) out.add(i); } // 동료 마피아 제외
                case MAFIA_COP, MAFIA_OBSERVER, MAFIA_BLOCKER -> {
                    boolean ability = mafiaAbilityMode.getOrDefault(mySeat, false);
                    if (ability ? (i != mySeat) : !isMafia(players.get(i).role)) out.add(i); // 능력=자기 제외, 살해=동료 제외
                }
                case DOCTOR -> out.add(i);            // 자기 보호 허용
                default -> { if (i != mySeat) out.add(i); } // 경찰/시민/관종/관찰자/봉쇄자: 자기 제외
            }
        }
        return out;
    }

    private List<Integer> aliveSeats() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) if (players.get(i).alive) out.add(i);
        return out;
    }

    private int seatOf(Player p) { return players.indexOf(p); }

    private Player requirePlayer(String clientId) {
        Integer s = clientSeats.get(clientId);
        if (s == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "참가하지 않은 기기입니다");
        return players.get(s);
    }

    private void addPlayer(String clientId, String nick) {
        int seat = players.size();
        players.add(new Player(clientId, trimNick(nick)));
        clientSeats.put(clientId, seat);
    }

    private void reset() {
        phase = null;
        phaseEndsAt = 0;
        round = 0;
        hostClientId = null;
        players.clear();
        clientSeats.clear();
        leftClients.clear();
        nightMs = 60_000; discussMs = 90_000; voteMs = 30_000;
        mafiaMin = 1; mafiaMax = 2;
        psychoMin = 0; psychoMax = 1;
        attentionMin = 0; attentionMax = 1;
        revealOnDeath = true;
        nightTargetBySeat.clear();
        copLog.clear();
        psychoCopLogs.clear();
        myLogs.clear();
        history.clear();
        nightActed.clear();
        psychoFakeRoles.clear();
        votes.clear();
        mafiaAbilityMode.clear();
        concealedSeats.clear();
        mafiaCopMin = mafiaCopMax = mafiaShadowMin = mafiaShadowMax = 0;
        observerMin = observerMax = blockerMin = blockerMax = 0;
        mafiaObserverMin = mafiaObserverMax = mafiaBlockerMin = mafiaBlockerMax = 0;
        abilityIndependentKill = false;
        blockedSeats.clear();
        defenseMs = 20_000; finalVoteMs = 20_000;
        accusedSeat = -1; finalVotes.clear();
        nightMessage = null;
        nightDeadSeat = executedSeat = -1;
        winner = null;
    }

    private int to0(int seat1) { return seat1 <= 0 ? -1 : seat1 - 1; }
    private int seat1(int seat0) { return seat0 < 0 ? -1 : seat0 + 1; }

    private static String trimNick(String nick) {
        String t = nick == null ? "" : nick.trim();
        if (t.isEmpty()) t = "익명";
        return t.length() > 16 ? t.substring(0, 16) : t;
    }

    private static int clampSec(Integer v, int min, int max, int def) {
        if (v == null) return def;
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(Integer v, int min, int max, int def) {
        if (v == null) return def;
        return Math.max(min, Math.min(max, v));
    }

    /** [min,max] 범위에서 랜덤(포함). */
    private static int randRange(int min, int max) {
        if (max < min) max = min;
        return min + ThreadLocalRandom.current().nextInt(max - min + 1);
    }
}
