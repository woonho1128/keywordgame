package com.wordplay.mafia;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.mafia.ai.MafiaBotRuntime;
import com.wordplay.mafia.dto.MafiaStateResponse;
import com.wordplay.mafia.dto.MafiaStateResponse.ChatView;
import com.wordplay.mafia.dto.MafiaStateResponse.PlayerView;
import com.wordplay.mafia.dto.MafiaStateResponse.VoteView;
import com.wordplay.mafia.dto.NewMafiaRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 마피아(완전 자동·타이머) 단일 전역 방. 인메모리로 관리(DB 미사용).
 *
 * 진행은 타이머로 자동. 별도 스레드 없이, 폴링/행동이 들어올 때마다 tick()이
 * "지금 ≥ 페이즈 종료시각"이면 다음 페이즈로 넘긴다(lazy advance). 모두가
 * 1초마다 폴링하므로 사실상 실시간으로 흘러간다.
 */
public class MafiaService implements RoomGame {

    enum Phase { LOBBY, NIGHT, MORNING, DISCUSS, VOTE, DEFENSE, FINAL_VOTE, EXECUTE, ENDED }
    enum Role { MAFIA, POLICE, DOCTOR, CITIZEN }

    private static final long MORNING_MS = 6_000;
    private static final long EXECUTE_MS = 6_000;
    private static final int MAX_BOTS = 5;
    private static final int MAX_BOT_CHAT_PER_DISCUSS = 10; // 봇당 토론 발언 총상한(비용 방어)
    private static final int BOT_PROACTIVE_CHAT = 3;        // 봇이 스스로 여는 발언 수(그 이상은 사람 말에 반응해서만)
    // 봇 발언 사이 최소 간격. 봇마다 따로 예약하면 여러 명이 겹쳐 1~2초 만에 도배되어
    // 사람이 읽을 틈이 없다. 실제 전송 직전에 이 간격을 강제한다.
    private static final long BOT_CHAT_MIN_GAP_MS = 4500;

    private static final class Player {
        final String clientId;
        String nick;
        Role role;
        boolean alive = true;
        boolean ai = false;         // AI 봇 여부
        String persona = null;      // AI 봇 성격(말투/태도), 사람은 null
        Player(String clientId, String nick) { this.clientId = clientId; this.nick = nick; }
    }

    /** AI 봇 성격 프리셋(랜덤 배정). */
    private static final List<String> PERSONAS = List.of(
            "공격적인 저격수. 의심 가는 사람을 세게 몰아붙이고 직설적으로 말한다.",
            "냉철한 논리파. 근거와 앞뒤 모순을 조목조목 따진다. 감정 표현은 거의 없다.",
            "장난기 많은 개그형. 'ㅋㅋ'를 자주 쓰고 농담을 섞어 가볍게 말한다.",
            "조용한 관찰자. 말수가 적고 핵심만 짧게 던진다. 차분한 말투.",
            "소심하고 우유부단. 확신 없이 '음.. 글쎄', '나도 잘 모르겠는데' 식으로 눈치를 본다.",
            "다혈질에 감정적. 발끈하고 억울해하며 강하게 반응한다.",
            "능글맞은 여우. 은근슬쩍 화제를 돌리고 남을 유도한다. 여유로운 말투.",
            "분위기를 주도하는 리더형. 상황을 정리하고 '오늘은 누구 가자'며 투표를 이끈다.");

    /** 토론 채팅 한 줄. */
    private record ChatMsg(int seat, String nick, String text, boolean ai, long round, long ts) {}

    // ---- 게임 상태 ----
    private Phase phase = null;              // null = 방 없음(NOT_STARTED)
    private long phaseEndsAt = 0;
    private long round = 0;
    private long lastActiveMs = System.currentTimeMillis();
    private String hostClientId = null;
    private final List<Player> players = new ArrayList<>();          // seat = index
    private final Map<String, Integer> clientSeats = new HashMap<>(); // clientId -> seat
    private final Set<String> leftClients = new HashSet<>();          // 진행/종료 중 방을 나간 클라이언트

    // 설정
    private long nightMs = 30_000, discussMs = 90_000, voteMs = 30_000;
    private int configMafiaCount = 0;        // 0 = 자동
    private boolean revealOnDeath = true;

    // 밤 행동
    private int copTarget = -1, doctorTarget = -1;
    private int lastDoctorTarget = -1;       // 직전 밤 의사 보호 대상(연속 보호 금지)
    private final List<String> copLog = new ArrayList<>();
    private final Map<Integer, Boolean> copFindings = new HashMap<>();  // 경찰만: 조사한 좌석 -> 마피아 여부
    private final Set<Integer> skipVotes = new HashSet<>();             // 토론 스킵에 동의한 좌석
    private final List<String> history = new ArrayList<>();             // 전체 공개 진행 이력
    private final Map<Integer, List<String>> myLogs = new HashMap<>();  // 좌석별 개인 이력(자기 능력/투표)
    private final Map<Integer, Integer> mafiaPicks = new HashMap<>();   // 마피아 seat -> 지목(실시간 공유·다수결)
    private final Map<Integer, Integer> citizenPicks = new HashMap<>(); // 시민 위장 지목(결과 무관)
    private final Set<Integer> nightActed = new HashSet<>();            // 이번 밤 지목을 마친 좌석

    // 낮 투표: 투표자 seat -> 대상 seat(-1 기권)
    private final Map<Integer, Integer> votes = new HashMap<>();
    private int accusedSeat = -1;                                     // 재판대에 오른 좌석
    private final Map<Integer, Boolean> finalVotes = new HashMap<>(); // 좌석 -> 사형(true)/생존(false)
    private long defenseMs = 20_000, finalVoteMs = 20_000;

    // 발표용 결과
    private String nightMessage = null;
    private int nightDeadSeat = -1;
    private int executedSeat = -1;
    private String winner = null;

    // ---- 채팅/AI 봇 ----
    private final List<ChatMsg> chat = new ArrayList<>();               // 토론 채팅(공개)
    private final AtomicInteger botCounter = new AtomicInteger(0);      // 봇 닉네임 번호
    private final MafiaBotRuntime bot;                                  // null이면 봇 비활성
    private final Set<Integer> botInFlight = new HashSet<>();           // LLM 호출 진행 중인 봇 좌석
    private final Map<Integer, Long> botNightAt = new HashMap<>();      // 봇 좌석 -> 밤 행동 시각
    private final Map<Integer, Long> botChatAt = new HashMap<>();       // 봇 좌석 -> 다음 발언 시각
    private final Map<Integer, String> botHeldChat = new HashMap<>();   // 봇 좌석 -> 간격 때문에 미뤄둔 발언
    private final Map<Integer, Long> botFinalAt = new HashMap<>();      // 봇 좌석 -> 사형/생존 판단 시각
    private final Set<Integer> botFinalInFlight = new HashSet<>();      // 사형/생존 LLM 호출 중인 봇 좌석
    private final Map<Integer, Integer> botChatCount = new HashMap<>(); // 봇 좌석 -> 이번 토론 발언 수
    private final Map<Integer, Long> botVoteAt = new HashMap<>();       // 봇 좌석 -> 투표 시각
    private long botPacingRound = -1;                                   // 토론 페이싱 초기화 기준 라운드

    public MafiaService() { this(null); }
    public MafiaService(MafiaBotRuntime bot) { this.bot = bot; }

    // =================== 명령 ===================

    /** 방 생성 + 방장 참가. */
    public synchronized MafiaStateResponse newGame(String clientId, NewMafiaRequest req) {
        reset();
        this.phase = Phase.LOBBY;
        this.hostClientId = clientId;
        this.nightMs = clampSec(req.nightSec(), 20, 180, 60) * 1000L;
        this.discussMs = clampSec(req.discussSec(), 15, 300, 90) * 1000L;
        this.voteMs = clampSec(req.voteSec(), 10, 120, 30) * 1000L;
        this.defenseMs = clampSec(req.defenseSec(), 5, 120, 20) * 1000L;
        this.finalVoteMs = clampSec(req.finalVoteSec(), 5, 120, 20) * 1000L;
        this.configMafiaCount = req.mafiaCount() == null ? 0 : Math.max(0, req.mafiaCount());
        this.revealOnDeath = req.revealOnDeath() == null || req.revealOnDeath();
        addPlayer(clientId, req.nick());
        return me(clientId);
    }

    /** 대기방 참가. */
    public synchronized MafiaStateResponse join(String clientId, String nick) {
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

    /** 방장이 게임 시작 → 역할 배정 후 첫 밤. */
    public synchronized MafiaStateResponse start(String clientId) {
        if (phase != Phase.LOBBY) throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 시작할 수 없습니다");
        if (!clientId.equals(hostClientId)) throw new BusinessException(ErrorCode.INVALID_INPUT, "방장만 시작할 수 있습니다");
        int n = players.size();
        if (n < 4) throw new BusinessException(ErrorCode.INVALID_INPUT, "최소 4명이 필요합니다");

        int mafia = configMafiaCount > 0 ? configMafiaCount : Math.max(1, n / 3);
        mafia = Math.min(mafia, Math.max(1, n - 3)); // 경찰1·의사1·시민1 최소 보장

        List<Role> roles = new ArrayList<>();
        for (int i = 0; i < mafia; i++) roles.add(Role.MAFIA);
        roles.add(Role.POLICE);
        roles.add(Role.DOCTOR);
        while (roles.size() < n) roles.add(Role.CITIZEN);
        Collections.shuffle(roles);
        for (int i = 0; i < n; i++) {
            players.get(i).role = roles.get(i);
            players.get(i).alive = true;
        }

        this.round = 1;
        this.lastDoctorTarget = -1;
        this.copLog.clear();
        this.history.clear();
        this.myLogs.clear();
        history.add("🎬 게임 시작 · " + n + "명 (마피아 " + mafia + "명)");
        prepareNight();
        startPhase(Phase.NIGHT);
        return me(clientId);
    }

    /** 밤 행동(역할에 따라 마피아 지목/경찰 조사/의사 보호). */
    public synchronized MafiaStateResponse nightAction(String clientId, int target) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.NIGHT) throw new BusinessException(ErrorCode.INVALID_INPUT, "지금은 밤이 아닙니다");
        if (!me.alive) throw new BusinessException(ErrorCode.INVALID_INPUT, "사망한 플레이어입니다");
        int t = to0(target);
        if (!selectableSeats(me).contains(t == -1 ? -99 : t))
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지목할 수 없는 대상입니다");

        switch (me.role) {
            case MAFIA -> mafiaPicks.put(seatOf(me), t);
            case POLICE -> copTarget = t; // 선택만 저장(밤 동안 변경 가능). 결과는 밤이 끝날 때 1건만 공개
            case DOCTOR -> doctorTarget = t;
            case CITIZEN -> citizenPicks.put(seatOf(me), t); // 위장 지목: 결과에 영향 없음
        }
        nightActed.add(seatOf(me));
        maybeAdvanceNight(); // 살아있는 전원이 지목을 마쳤을 때만 조기 진행
        return me(clientId);
    }

    /** 낮 투표. target=-1 기권. */
    public synchronized MafiaStateResponse vote(String clientId, int target) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.VOTE) throw new BusinessException(ErrorCode.INVALID_INPUT, "지금은 투표 시간이 아닙니다");
        if (!me.alive) throw new BusinessException(ErrorCode.INVALID_INPUT, "사망한 플레이어입니다");
        int t = to0(target);
        if (t != -1 && !aliveSeats().contains(t))
            throw new BusinessException(ErrorCode.INVALID_INPUT, "투표할 수 없는 대상입니다");
        if (t == seatOf(me)) throw new BusinessException(ErrorCode.INVALID_INPUT, "자신에게 투표할 수 없습니다");
        votes.put(seatOf(me), t);
        maybeAdvanceVote();
        return me(clientId);
    }

    /** 사형/생존 투표(최후변론 후). 재판 당사자는 투표 불가. execute=true(사형)/false(생존). */
    public synchronized MafiaStateResponse finalVote(String clientId, boolean execute) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.FINAL_VOTE) throw new BusinessException(ErrorCode.INVALID_INPUT, "지금은 사형/생존 투표 시간이 아닙니다");
        if (!me.alive) throw new BusinessException(ErrorCode.INVALID_INPUT, "사망한 플레이어입니다");
        int seat = seatOf(me);
        if (seat == accusedSeat) throw new BusinessException(ErrorCode.INVALID_INPUT, "재판 당사자는 투표할 수 없습니다");
        finalVotes.put(seat, execute);
        maybeAdvanceFinalVote();
        return me(clientId);
    }

    private void maybeAdvanceFinalVote() {
        long eligible = aliveSeats().stream().filter(s -> s != accusedSeat).count();
        if (finalVotes.size() >= eligible) advance();
    }

    /** 토론 스킵 동의. 살아있는 전원이 동의하면 즉시 투표로 넘어간다. */
    public synchronized MafiaStateResponse skipDiscuss(String clientId) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.DISCUSS) throw new BusinessException(ErrorCode.INVALID_INPUT, "지금은 토론 시간이 아닙니다");
        if (!me.alive) throw new BusinessException(ErrorCode.INVALID_INPUT, "사망한 플레이어입니다");
        skipVotes.add(seatOf(me));
        if (skipVotes.containsAll(aliveSeats())) advance();
        return me(clientId);
    }

    /** 관리자: AI 봇 추가(대기방에서만, 최대 {@value #MAX_BOTS}명). */
    public synchronized MafiaStateResponse addBots(int count) {
        if (bot == null || !bot.available())
            throw new BusinessException(ErrorCode.INVALID_INPUT, "AI 봇이 설정되어 있지 않습니다(OpenAI 키 필요)");
        if (phase != Phase.LOBBY)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "대기방에서만 봇을 추가할 수 있습니다");
        int botsNow = (int) players.stream().filter(p -> p.ai).count();
        int want = Math.min(Math.max(1, count), MAX_BOTS - botsNow);
        if (want <= 0) throw new BusinessException(ErrorCode.INVALID_INPUT, "봇은 최대 " + MAX_BOTS + "명까지입니다");
        for (int i = 0; i < want && players.size() < 12; i++) {
            int n = botCounter.incrementAndGet();
            Player b = new Player("bot::" + n + "::" + System.nanoTime(), "🤖 봇" + n);
            b.ai = true;
            b.persona = pickPersona();
            players.add(b);
            clientSeats.put(b.clientId, players.size() - 1);
        }
        return buildResponse(hostClientId);
    }

    /** 낮(아침/토론/투표) 동안 채팅 한 줄 전송. */
    public synchronized MafiaStateResponse sendChat(String clientId, String text) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.MORNING && phase != Phase.DISCUSS && phase != Phase.VOTE)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금은 대화할 수 없습니다");
        if (!me.alive) throw new BusinessException(ErrorCode.INVALID_INPUT, "사망한 플레이어는 대화할 수 없습니다");
        String t = cleanChat(text);
        if (t.isEmpty()) return buildResponse(clientId);
        chat.add(new ChatMsg(seatOf(me), me.nick, t, false, round, System.currentTimeMillis()));
        pokeBotsForReply(); // 봇이 사람 말에 반응하도록
        return me(clientId);
    }

    /** 관리자 초기화. */
    public synchronized MafiaStateResponse resetGame() {
        reset();
        return MafiaStateResponse.notStarted(System.currentTimeMillis());
    }

    /** 폴링. */
    public synchronized MafiaStateResponse me(String clientId) {
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
    @Override public synchronized String hostLabel() { return players.isEmpty() ? "" : players.get(0).nick; }
    @Override public synchronized boolean isEnded() { return phase == Phase.ENDED; }
    @Override public synchronized long lastActiveMs() { return lastActiveMs; }
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

    // =================== 타이머/진행 ===================

    private void tick() {
        long now = System.currentTimeMillis();
        int guard = 0;
        while (phase != null && phase != Phase.LOBBY && phase != Phase.ENDED
                && phaseEndsAt > 0 && now >= phaseEndsAt && guard++ < 30) {
            advance();
        }
        driveBots(System.currentTimeMillis());
    }

    private void maybeAdvanceNight() {
        if (nightActed.containsAll(aliveSeats())) advance();
    }

    private void maybeAdvanceVote() {
        if (votes.size() >= aliveSeats().size()) advance();
    }

    private void advance() {
        switch (phase) {
            case NIGHT -> { resolveNight(); if (!checkWin()) startPhase(Phase.MORNING); }
            case MORNING -> { skipVotes.clear(); startPhase(Phase.DISCUSS); initBotDiscuss(); }
            case DISCUSS -> { votes.clear(); startPhase(Phase.VOTE); initBotVote(); }
            case VOTE -> {
                resolveNomination();
                if (accusedSeat >= 0) { finalVotes.clear(); startPhase(Phase.DEFENSE); }
                else { executedSeat = -1; startPhase(Phase.EXECUTE); }
            }
            case DEFENSE -> { finalVotes.clear(); startPhase(Phase.FINAL_VOTE); initBotFinalVote(); }
            case FINAL_VOTE -> { resolveFinalVote(); if (!checkWin()) startPhase(Phase.EXECUTE); }
            case EXECUTE -> { round++; prepareNight(); startPhase(Phase.NIGHT); }
            default -> { }
        }
    }

    private void startPhase(Phase p) {
        this.phase = p;
        long now = System.currentTimeMillis();
        this.phaseEndsAt = switch (p) {
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
        mafiaPicks.clear();
        copTarget = -1;
        doctorTarget = -1;
        citizenPicks.clear();
        nightActed.clear();
        botNightAt.clear();
    }

    private void resolveNight() {
        nightDeadSeat = -1;
        int mafiaTarget = pluralityTarget(mafiaPicks); // 마피아 다수결(동수는 낮은 좌석)
        if (mafiaTarget >= 0 && mafiaTarget != doctorTarget && players.get(mafiaTarget).alive) {
            players.get(mafiaTarget).alive = false;
            nightDeadSeat = mafiaTarget;
            nightMessage = players.get(mafiaTarget).nick + "님이 밤 사이 사망했습니다.";
        } else {
            nightMessage = "평화로운 밤이었습니다. 아무도 죽지 않았습니다.";
        }
        history.add(round + "일차 🌙 " + nightMessage);
        // 경찰 조사 결과: 최종 지목 1명만 아침에 공개
        int policeSeat = seatOfRole(Role.POLICE);
        if (copTarget >= 0 && copTarget < players.size()) {
            boolean isMafia = players.get(copTarget).role == Role.MAFIA;
            copLog.add(round + "일차: " + players.get(copTarget).nick + " → " + (isMafia ? "마피아 O" : "마피아 X"));
            copFindings.put(copTarget, isMafia);
            if (policeSeat >= 0)
                myLog(policeSeat).add(round + "일차 🔎 조사: " + players.get(copTarget).nick + " → " + (isMafia ? "마피아" : "시민"));
        }
        // 개인 밤 행동 기록(자기만 봄)
        int docSeat = seatOfRole(Role.DOCTOR);
        if (docSeat >= 0 && doctorTarget >= 0) {
            boolean saved = mafiaTarget >= 0 && mafiaTarget == doctorTarget;
            myLog(docSeat).add(round + "일차 💉 보호: " + players.get(doctorTarget).nick
                    + (saved ? " ⭕ 성공 (마피아 공격을 막음!)" : " ❌ (마피아가 노린 대상이 아니었음)"));
        }
        String killResult = nightDeadSeat >= 0 ? players.get(nightDeadSeat).nick + " 처치 성공" : "아무도 죽지 않음(보호/실패)";
        for (var e : mafiaPicks.entrySet())
            if (e.getValue() >= 0)
                myLog(e.getKey()).add(round + "일차 🔪 지목: " + players.get(e.getValue()).nick + " · 결과: " + killResult);
        lastDoctorTarget = doctorTarget; // 다음 밤 연속 보호 금지용
    }

    /** 지목 맵에서 최다 득표 대상(동수면 낮은 좌석). 없으면 -1. */
    private int pluralityTarget(Map<Integer, Integer> picks) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int t : picks.values()) if (t >= 0) counts.merge(t, 1, Integer::sum);
        int best = -1, bestCount = 0;
        for (var e : counts.entrySet()) {
            if (e.getValue() > bestCount || (e.getValue() == bestCount && e.getKey() < best)) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    /** 낮 투표 → 최다 득표자를 재판대에 올린다(아직 처형 안 함). 동표·기권이면 지목 없음. */
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

    /** 사형/생존 투표 집계 → 사형 표가 더 많으면 처형. */
    private void resolveFinalVote() {
        executedSeat = -1;
        int kill = 0, spare = 0;
        for (boolean v : finalVotes.values()) { if (v) kill++; else spare++; }
        history.add(round + "일차 ⚖️ 사형투표: 사형 " + kill + " · 생존 " + spare);
        if (accusedSeat >= 0 && kill > spare && players.get(accusedSeat).alive) {
            players.get(accusedSeat).alive = false;
            executedSeat = accusedSeat;
            history.add(round + "일차 ☀️ " + players.get(accusedSeat).nick + "님 처형 (정체: " + roleKor(players.get(accusedSeat).role) + ")");
        } else {
            history.add(round + "일차 ☀️ " + (accusedSeat >= 0 ? players.get(accusedSeat).nick + "님 생존 (사형 부결)" : "처형 없음"));
        }
        accusedSeat = -1;
    }

    private List<String> myLog(int seat) { return myLogs.computeIfAbsent(seat, k -> new ArrayList<>()); }

    private int seatOfRole(Role r) {
        for (int i = 0; i < players.size(); i++) if (players.get(i).role == r) return i;
        return -1;
    }

    private static String roleKor(Role r) {
        return switch (r) {
            case MAFIA -> "마피아"; case POLICE -> "경찰"; case DOCTOR -> "의사"; default -> "시민";
        };
    }

    private boolean checkWin() {
        long mafiaAlive = players.stream().filter(p -> p.alive && p.role == Role.MAFIA).count();
        long citizenAlive = players.stream().filter(p -> p.alive && p.role != Role.MAFIA).count();
        if (mafiaAlive == 0) { winner = "CITIZEN"; phase = Phase.ENDED; phaseEndsAt = 0; history.add("🏁 시민팀 승리!"); return true; }
        if (mafiaAlive >= citizenAlive) { winner = "MAFIA"; phase = Phase.ENDED; phaseEndsAt = 0; history.add("🏁 마피아팀 승리!"); return true; }
        return false;
    }

    // =================== AI 봇 ===================

    /** tick 끝에서 현재 페이즈에 맞는 봇 행동을 구동. */
    private void driveBots(long now) {
        if (bot == null || !bot.available() || phase == null) return;
        if (players.stream().noneMatch(p -> p.ai)) return;
        switch (phase) {
            case NIGHT -> driveBotNight(now);
            case DISCUSS -> driveBotDiscuss(now);
            case VOTE -> driveBotVote(now);
            case FINAL_VOTE -> driveBotFinalVote(now);
            default -> { }
        }
    }

    /**
     * 봇 사형/생존 투표.
     *
     * <p>마피아는 정체를 아니까 규칙으로 즉시 정한다(동료면 생존, 시민이면 사형).
     * 시민은 예전에 무조건 '사형'을 눌렀는데, 그러면 지목만 당하면 무조건 죽어서
     * 마피아가 시민 하나만 몰면 이기는 판이 됐다. 이제 토론 내용을 보고 LLM이 정한다.
     */
    private void driveBotFinalVote(long now) {
        if (accusedSeat < 0) return;
        boolean accusedMafia = players.get(accusedSeat).role == Role.MAFIA;
        boolean acted = false;
        for (int seat : aliveSeats()) {
            if (seat == accusedSeat) continue;
            Player p = players.get(seat);
            if (!p.ai || finalVotes.containsKey(seat)) continue;

            if (p.role == Role.MAFIA) {                 // 동료면 살리고, 시민이면 죽인다
                finalVotes.put(seat, !accusedMafia);
                acted = true;
                continue;
            }
            if (botFinalInFlight.contains(seat)) continue;
            if (phaseEndsAt - now < 4000) {             // 마감 임박: 판단 못 했으면 사형(기존 동작)
                finalVotes.put(seat, true);
                acted = true;
                continue;
            }
            if (now < botFinalAt.getOrDefault(seat, Long.MAX_VALUE)) continue;
            botFinalAt.put(seat, Long.MAX_VALUE);
            botFinalInFlight.add(seat);
            long r = round;
            String user = buildFinalVotePrompt(p);
            bot.submit(() -> applyBotFinalVote(seat, r, bot.finalVote(user)));
        }
        if (acted) maybeAdvanceFinalVote();
    }

    synchronized void applyBotFinalVote(int seat, long r, String out) {
        botFinalInFlight.remove(seat);
        if (phase != Phase.FINAL_VOTE || r != round || seat >= players.size()) return;
        Player p = players.get(seat);
        if (!p.alive || !p.ai || finalVotes.containsKey(seat) || seat == accusedSeat) return;
        finalVotes.put(seat, parseFinalVote(out));
        maybeAdvanceFinalVote();
    }

    /** '생존'이라고 분명히 말했을 때만 살린다. 알아들을 수 없으면 사형(기존 동작 유지). */
    static boolean parseFinalVote(String out) {
        if (out == null) return true;
        String t = out.replaceAll("\\s+", "");
        if (t.contains("생존") || t.contains("살린") || t.contains("살려")) return false;
        return true;
    }

    private void initBotFinalVote() {
        botFinalAt.clear();
        botFinalInFlight.clear();
        long now = System.currentTimeMillis();
        for (int seat : aliveSeats())
            if (players.get(seat).ai) botFinalAt.put(seat, now + 800 + rnd(2500));
    }

    // --- 밤: 규칙 기반(즉시·무료) ---
    private void driveBotNight(long now) {
        boolean acted = false;
        for (int seat : aliveSeats()) {
            Player p = players.get(seat);
            if (!p.ai || nightActed.contains(seat)) continue;
            long due = botNightAt.computeIfAbsent(seat, s -> now + 700 + rnd(2500));
            if (now < due) continue;
            int target = chooseNightTarget(p);
            if (target >= 0) {
                switch (p.role) {
                    case MAFIA -> mafiaPicks.put(seat, target);
                    case POLICE -> copTarget = target;
                    case DOCTOR -> doctorTarget = target;
                    case CITIZEN -> citizenPicks.put(seat, target);
                }
            }
            nightActed.add(seat);
            acted = true;
        }
        if (acted) maybeAdvanceNight();
    }

    /** 역할별 밤 대상 선택(생존자 중). 없으면 -1. */
    private int chooseNightTarget(Player p) {
        List<Integer> alive = aliveSeats();
        int myS = seatOf(p);
        switch (p.role) {
            case MAFIA -> {
                List<Integer> targets = new ArrayList<>();
                for (int i : alive) if (players.get(i).role != Role.MAFIA) targets.add(i);
                if (targets.isEmpty()) return -1;
                // 모든 마피아 봇이 같은 대상을 고르도록 라운드 기반 결정.
                return targets.get((int) Math.floorMod(round * 2654435761L, (long) targets.size()));
            }
            case POLICE -> {
                List<Integer> unchecked = new ArrayList<>();
                for (int i : alive) if (i != myS && !copFindings.containsKey(i)) unchecked.add(i);
                List<Integer> pool = !unchecked.isEmpty() ? unchecked
                        : alive.stream().filter(i -> i != myS).toList();
                return pool.isEmpty() ? -1 : pool.get(rnd(pool.size()));
            }
            case DOCTOR -> {
                List<Integer> pool = alive.stream().filter(i -> i != lastDoctorTarget).toList();
                return pool.isEmpty() ? -1 : pool.get(rnd(pool.size()));
            }
            default -> {
                List<Integer> pool = alive.stream().filter(i -> i != myS).toList();
                return pool.isEmpty() ? -1 : pool.get(rnd(pool.size()));
            }
        }
    }

    // --- 토론: LLM 채팅(비동기, 페이싱) ---
    /** 마지막으로 봇 발언이 실제로 표시된 시각(전역 간격 강제용). */
    private long lastBotChatMs = 0;

    private void initBotDiscuss() {
        botPacingRound = round;
        lastBotChatMs = 0;
        botChatCount.clear();
        botChatAt.clear();
        botHeldChat.clear();
        long now = System.currentTimeMillis();
        int i = 0;
        for (int seat : aliveSeats()) {
            if (!players.get(seat).ai) continue;
            botChatCount.put(seat, 0);
            botChatAt.put(seat, now + 2500 + 4500L * (i++) + rnd(2000));
        }
    }

    private void driveBotDiscuss(long now) {
        for (int seat : aliveSeats()) {
            Player p = players.get(seat);
            if (!p.ai || botInFlight.contains(seat)) continue;
            if (botChatCount.getOrDefault(seat, MAX_BOT_CHAT_PER_DISCUSS) >= MAX_BOT_CHAT_PER_DISCUSS) continue;
            if (now < botChatAt.getOrDefault(seat, Long.MAX_VALUE)) continue;
            // 간격 때문에 미뤄둔 말이 있으면 새로 물어보지 말고 그걸 내보낸다(호출 낭비 방지).
            String held = botHeldChat.remove(seat);
            if (held != null) { postBotChat(seat, p, held, now); continue; }
            botChatAt.put(seat, Long.MAX_VALUE);       // 응답 올 때까지 정지
            botInFlight.add(seat);
            long r = round;
            String user = buildChatPrompt(p);
            bot.submit(() -> applyBotChat(seat, r, bot.chat(user)));
        }
    }

    synchronized void applyBotChat(int seat, long r, String text) {
        botInFlight.remove(seat);
        if (phase != Phase.DISCUSS || r != round || seat >= players.size()) return;
        Player p = players.get(seat);
        if (!p.alive || !p.ai) return;
        long now = System.currentTimeMillis();
        String t = cleanChat(text);
        // 직전 봇 발언과 너무 붙으면 조금 미뤘다가 말한다(여러 봇이 겹쳐 도배되는 것 방지).
        // 봇이 늘수록 이 충돌이 잦아지므로, 받아둔 말을 버리지 말고 들고 있다가 그대로 내보낸다.
        if (!t.isEmpty() && now - lastBotChatMs < BOT_CHAT_MIN_GAP_MS) {
            botHeldChat.put(seat, t);
            botChatAt.put(seat, lastBotChatMs + BOT_CHAT_MIN_GAP_MS + rnd(1500));
            return;
        }
        postBotChat(seat, p, t, now);
    }

    /** 정리된 발언을 실제로 채팅에 올리고 다음 발언을 예약한다. */
    private void postBotChat(int seat, Player p, String t, long now) {
        if (!t.isEmpty() && isRepetitive(t)) t = "";   // 방금 나온 말과 사실상 같은 말은 버린다
        if (!t.isEmpty()) {
            chat.add(new ChatMsg(seat, p.nick, t, true, round, System.currentTimeMillis()));
            botChatCount.merge(seat, 1, Integer::sum);
            lastBotChatMs = now;
        }
        // 스스로 여는 발언은 BOT_PROACTIVE_CHAT까지만. 그 이상은 사람이 말 걸 때(poke) 반응.
        botChatAt.put(seat, Long.MAX_VALUE);
        if (botChatCount.getOrDefault(seat, 0) < BOT_PROACTIVE_CHAT && phaseEndsAt - now > 6000)
            botChatAt.put(seat, now + 6000 + rnd(8000));
    }

    /** 사람이 토론 중 발언하면 봇 1~2명이 곧 반응하도록 예약. */
    private void pokeBotsForReply() {
        if (phase != Phase.DISCUSS) return;
        long now = System.currentTimeMillis();
        List<Integer> idle = new ArrayList<>();
        for (int seat : aliveSeats()) {
            Player p = players.get(seat);
            if (!p.ai || botInFlight.contains(seat)) continue;
            if (botChatCount.getOrDefault(seat, 0) >= MAX_BOT_CHAT_PER_DISCUSS) continue;
            if (botChatAt.getOrDefault(seat, Long.MAX_VALUE) <= now + 2500) continue; // 이미 곧 말할 예정
            idle.add(seat);
        }
        if (idle.isEmpty()) return;
        Collections.shuffle(idle);
        int replies = Math.min(idle.size(), 1 + rnd(2)); // 1~2명만 반응(전원이 우르르 답하면 어색)
        for (int k = 0; k < replies; k++) {
            botHeldChat.remove(idle.get(k));  // 방금 사람이 한 말에 답해야 하니 미뤄둔 옛 발언은 버린다
            botChatAt.put(idle.get(k), now + 2000 + 2500L * k + rnd(1500));
        }
    }

    // --- 투표: LLM 결정(비동기), 실패·마감임박 시 규칙 폴백 ---
    private void initBotVote() {
        botVoteAt.clear();
        long now = System.currentTimeMillis();
        for (int seat : aliveSeats())
            if (players.get(seat).ai) botVoteAt.put(seat, now + 1200 + rnd(3500));
    }

    private void driveBotVote(long now) {
        boolean acted = false;
        for (int seat : aliveSeats()) {
            Player p = players.get(seat);
            if (!p.ai || votes.containsKey(seat) || botInFlight.contains(seat)) continue;
            if (phaseEndsAt - now < 3500) {            // 마감 임박: 규칙으로 즉시 투표
                votes.put(seat, heuristicVote(p));
                acted = true;
                continue;
            }
            if (now < botVoteAt.getOrDefault(seat, Long.MAX_VALUE)) continue;
            botVoteAt.put(seat, Long.MAX_VALUE);
            botInFlight.add(seat);
            long r = round;
            String user = buildVotePrompt(p);
            bot.submit(() -> applyBotVote(seat, r, bot.vote(user)));
        }
        if (acted) maybeAdvanceVote();
    }

    synchronized void applyBotVote(int seat, long r, String out) {
        botInFlight.remove(seat);
        if (phase != Phase.VOTE || r != round || seat >= players.size()) return;
        Player p = players.get(seat);
        if (!p.alive || !p.ai || votes.containsKey(seat)) return;
        votes.put(seat, parseVote(out, p));
        maybeAdvanceVote();
    }

    /** LLM 출력에서 좌석 번호 파싱. 유효하지 않으면 규칙 폴백. */
    private int parseVote(String out, Player p) {
        if (out != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+").matcher(out);
            if (m.find()) {
                int seat1 = Integer.parseInt(m.group());
                if (seat1 == 0) return -1;             // 기권
                int s0 = seat1 - 1;
                if (s0 != seatOf(p) && aliveSeats().contains(s0)) return s0;
            }
        }
        return heuristicVote(p);
    }

    /** 규칙 기반 투표(폴백): 마피아 봇은 비마피아 랜덤, 그 외는 기권. */
    private int heuristicVote(Player p) {
        if (p.role == Role.MAFIA) {
            List<Integer> targets = aliveSeats().stream()
                    .filter(i -> i != seatOf(p) && players.get(i).role != Role.MAFIA).toList();
            if (!targets.isEmpty()) return targets.get(rnd(targets.size()));
        }
        return -1;
    }

    // --- 프롬프트(닉네임 기준, 정보 격리: 각 사람이 아는 것만) ---
    private String buildChatPrompt(Player p) {
        List<String> others = new ArrayList<>();
        for (int i : aliveSeats()) if (i != seatOf(p)) others.add(players.get(i).nick);
        String last = lastOtherSpeaker(p);
        return chatContext()
                + "\n\n[너의 정보] 이 대화에서 너의 이름은 '" + p.nick + "'다. " + rolePrivate(p)
                + (p.persona != null ? "\n[너의 성격] " + p.persona + " 이 성격이 말투와 태도에 자연스럽게 드러나게 해라." : "")
                + "\n[중요 규칙]"
                + "\n- 너는 '" + p.nick + "'다. 절대 너 자신('" + p.nick + "')을 의심하거나 남처럼 3인칭으로 부르지 마라."
                + "\n- 의심하거나 언급할 수 있는 상대는 너를 뺀 이들뿐: " + String.join(", ", others) + "."
                + "\n- 이미 대화에 나온 말을 그대로 반복하지 마라. 매번 새로운 내용이나 앞사람 말에 대한 반응을 말해라."
                + (last != null ? "\n- 방금 '" + last + "'가 말했다. 특히 너에게 묻거나 너를 지목한 말이 있으면 회피하지 말고 그 말에 직접 대꾸해라." : "")
                + "\n[이번 발언에서 할 일] " + chatIntent(p)
                + "\n지금 토론방에 사람처럼 딱 한 줄만 보내라. 설명·따옴표 없이 대사만.";
    }

    private String buildVotePrompt(Player p) {
        StringBuilder map = new StringBuilder();
        for (int i : aliveSeats()) if (i != seatOf(p))
            map.append(i + 1).append("=").append(players.get(i).nick).append("  ");
        return chatContext()
                + "\n\n[너의 정보] 너의 이름은 '" + p.nick + "'다. " + rolePrivate(p)
                + (p.persona != null ? "\n[너의 성격] " + p.persona : "")
                + "\n이제 처형 투표다. 후보(번호=이름): " + map.toString().trim()
                + "\n[판단 기준] 누가 누구를 몰았고 그 지목에 실제 근거가 있었는지 따져라."
                + " 분위기가 쏠린다는 이유만으로 따라 찍지 마라."
                + " 추리를 열심히 하던 사람이 갑자기 몰리고 있다면 그 몰이를 시작한 쪽을 의심해라."
                + "\n누굴 처형할지 위 번호 중 하나만 숫자로 답하라. 기권은 0. 다른 말 없이 숫자만 출력.";
    }

    /** 사형/생존 최종 판단(시민 봇만 씀. 마피아는 정체를 알아서 규칙으로 정한다). */
    private String buildFinalVotePrompt(Player p) {
        String accused = accusedSeat >= 0 ? players.get(accusedSeat).nick : "?";
        StringBuilder who = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : votes.entrySet())
            if (e.getValue() != null && e.getValue() == accusedSeat)
                who.append(players.get(e.getKey()).nick).append(" ");
        return chatContext()
                + "\n\n[너의 정보] 너의 이름은 '" + p.nick + "'다. " + rolePrivate(p)
                + (p.persona != null ? "\n[너의 성격] " + p.persona : "")
                + "\n\n[최종 판단] 지금 '" + accused + "'가 재판대에 올랐다."
                + (who.length() > 0 ? " 이 사람에게 투표한 건: " + who.toString().trim() + "." : "")
                + "\n죽여도 될 만큼 확실한 근거가 있는지만 봐라. 근거가 말투·추측·분위기뿐이면 살려라."
                + " 처형은 하루 한 명뿐이라 헛으로 쓰면 시민이 진다."
                + " 반대로 네가 아는 정보로 마피아가 거의 확실하면 사형이다."
                + "\n'사형' 또는 '생존' 둘 중 하나만 출력. 다른 말은 쓰지 마라.";
    }

    /** 이름(닉네임) 기준의 공개 상황·대화 로그. 좌석번호는 넣지 않아 혼동을 막는다. */
    private String chatContext() {
        StringBuilder sb = new StringBuilder();
        sb.append(round).append("일차 낮 토론. 지금 살아있는 사람: ");
        List<String> alive = new ArrayList<>();
        for (int i : aliveSeats()) alive.add(players.get(i).nick);
        sb.append(String.join(", ", alive)).append(".");
        int from = Math.max(0, history.size() - 5);
        if (from < history.size()) {
            sb.append("\n[지금까지 밤·처형 결과]");
            for (int i = from; i < history.size(); i++) sb.append("\n- ").append(history.get(i));
        }
        List<ChatMsg> today = chat.stream().filter(c -> c.round() == round).toList();
        if (today.isEmpty()) sb.append("\n[대화] 아직 아무도 말하지 않았다. 네가 먼저 말을 꺼내라.");
        else {
            sb.append("\n[대화 내용]");
            for (ChatMsg c : today) sb.append("\n").append(c.nick()).append(": ").append(c.text());
        }
        return sb.toString();
    }

    /**
     * 이번 턴에 무엇을 할지 정해준다.
     *
     * <p>지시가 없으면 봇이 매번 "쟤 수상해"만 반복해서 추리에 필요한 정보가 전혀 쌓이지 않는다
     * (근거 없이 서로 몰다가 아무나 지목하는 판이 된다). 그래서 발언 목적을 돌려가며 준다 —
     * 지목당했으면 변호, 초반엔 사실 정리와 질문, 근거가 모인 뒤에야 의견 제시.
     */
    private String chatIntent(Player p) {
        int mySeat = seatOf(p);
        for (int i = chat.size() - 1; i >= 0; i--) {
            ChatMsg c = chat.get(i);
            if (c.round() != round || c.seat() == mySeat) continue;
            if (c.text().contains(p.nick))
                return "누가 너를 지목하거나 언급했다. 딴소리 말고 그 말에 직접 반박하거나 해명하고, "
                        + "네가 아닌 이유를 하나라도 구체적으로 대라.";
        }
        return switch (botChatCount.getOrDefault(mySeat, 0)) {
            case 0 -> "아직 근거가 없다. 남을 지목하지 말고, 밤에 일어난 일이나 어제 투표에서 드러난 "
                    + "사실을 짚어 정리해라(누가 죽었는지, 평화로운 밤이었는지 등).";
            case 1 -> "특정한 한 사람에게 구체적으로 질문해라(어젯밤 뭘 했는지, 왜 그 사람한테 투표했는지, "
                    + "역할이 뭔지 등). 이번엔 의심한다는 말은 쓰지 마라.";
            default -> "지금까지 나온 말을 근거로 의견을 내라. 근거를 댈 수 없으면 지목하지 말고 "
                    + "판단을 미루거나 더 물어봐라.";
        };
    }

    /** 이번 라운드에서 나 아닌 사람이 마지막으로 한 발언자 닉네임. 없으면 null. */
    private String lastOtherSpeaker(Player p) {
        for (int i = chat.size() - 1; i >= 0; i--) {
            ChatMsg c = chat.get(i);
            if (c.round() == round && c.seat() != seatOf(p)) return c.nick();
        }
        return null;
    }

    /** viewer가 실제로 아는 자기 역할·비밀 정보(정보 격리). 닉네임 기준. */
    private String rolePrivate(Player p) {
        switch (p.role) {
            case MAFIA -> {
                List<String> fellows = new ArrayList<>();
                for (int i = 0; i < players.size(); i++)
                    if (i != seatOf(p) && players.get(i).role == Role.MAFIA)
                        fellows.add(players.get(i).nick);
                String team = fellows.isEmpty() ? "동료 마피아는 없다(너 혼자다)."
                        : "동료 마피아는 " + String.join(", ", fellows) + "다(이들은 절대 의심 말고 은근히 감싸라).";
                return "너의 정체는 [마피아]. " + team + " 정체를 숨기고 시민인 척하며 다른 사람을 의심하게 유도하라.";
            }
            case POLICE -> {
                List<String> found = new ArrayList<>();
                for (var e : copFindings.entrySet())
                    found.add(players.get(e.getKey()).nick + "은(는) " + (e.getValue() ? "마피아" : "시민"));
                String info = found.isEmpty() ? "아직 조사 결과가 없다."
                        : "너의 밤 조사 결과: " + String.join(", ", found) + ". 이 사실을 근거로 삼아라.";
                return "너의 정체는 [경찰]. " + info + " 단, 대놓고 '나 경찰'이라 밝히면 밤에 죽으니 조심히 몰아가라.";
            }
            case DOCTOR -> {
                return "너의 정체는 [의사]. 정체를 숨기고 일반 시민처럼 추리에 참여하라.";
            }
            default -> {
                return "너의 정체는 [시민]. 특별한 정보는 없다. 대화의 모순과 투표 행태로 마피아를 추리하라.";
            }
        }
    }

    /** 이미 배정된 성격을 피해 랜덤 배정(다 쓰면 중복 허용). */
    private String pickPersona() {
        Set<String> used = new HashSet<>();
        for (Player p : players) if (p.ai && p.persona != null) used.add(p.persona);
        List<String> pool = new ArrayList<>();
        for (String s : PERSONAS) if (!used.contains(s)) pool.add(s);
        if (pool.isEmpty()) pool = PERSONAS;
        return pool.get(rnd(pool.size()));
    }

    private static int rnd(int bound) { return bound <= 0 ? 0 : ThreadLocalRandom.current().nextInt(bound); }

    private static String cleanChat(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("^[\"'\\s]+|[\"'\\s]+$", "").replaceAll("\\s+", " ");
        // 모델이 가끔 다른 문자체계(예: 데바나가리·구자라트)를 섞어 뱉는다. 한국어 채팅에
        // 쓰이지 않는 글자는 통째로 지운다(한글/영숫자/기본 문장부호/이모지만 남김).
        t = t.replaceAll("[^\\p{IsHangul}\\p{IsLatin}0-9\\s.,!?~…·:;'\"()\\-\\u1100-\\u11FF\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u27BF]", "");
        // "음…", "흠..", "아…" 처럼 매번 같은 감탄사로 시작하면 말투가 단조로워진다.
        t = t.replaceFirst("^(음+|흠+|아+|어+)[\\s.…·,~]*", "").trim();
        t = t.replaceAll("\\s+", " ").trim();
        return t.length() > 120 ? t.substring(0, 120) : t;
    }

    /** 최근 봇 발언과 사실상 같은 말인지(도입부가 겹치거나 단어가 대부분 겹치면 중복). */
    private boolean isRepetitive(String t) {
        String key = normForCompare(t);
        if (key.length() < 4) return false;
        int checked = 0;
        for (int i = chat.size() - 1; i >= 0 && checked < 6; i--) {
            ChatMsg m = chat.get(i);
            if (!m.ai() || m.round() != round) continue;
            checked++;
            String prev = normForCompare(m.text());
            if (prev.isEmpty()) continue;
            if (prev.equals(key)) return true;
            if (key.length() >= 8 && prev.length() >= 8
                    && key.substring(0, 8).equals(prev.substring(0, 8))) return true;
        }
        return false;
    }
    private static String normForCompare(String s) {
        return s == null ? "" : s.replaceAll("[^\\p{IsHangul}0-9]", "");
    }

    // =================== 응답 빌드 ===================

    private MafiaStateResponse buildResponse(String clientId) {
        long now = System.currentTimeMillis();
        if (phase == null) return MafiaStateResponse.notStarted(now);

        boolean ended = phase == Phase.ENDED;
        Integer mySeatIdx = clientSeats.get(clientId);
        Player me = mySeatIdx == null ? null : players.get(mySeatIdx);
        boolean joined = me != null;

        // 플레이어 보드(역할은 종료 시 또는 사망+공개옵션일 때만 노출)
        boolean iAmPolice = joined && me.role == Role.POLICE;
        List<PlayerView> board = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            String shownRole = null;
            if (p.role != null && (ended || (revealOnDeath && !p.alive))) shownRole = p.role.name();
            // 경찰에게만: 내가 조사한 사람은 시민/마피아 표시
            String copResult = null;
            if (iAmPolice && copFindings.containsKey(i)) copResult = copFindings.get(i) ? "MAFIA" : "CITIZEN";
            board.add(new PlayerView(i + 1, p.nick, p.alive, shownRole, copResult, p.ai));
        }

        String actionKind = "NONE";
        List<Integer> selectable = List.of();
        int myTarget = -1;
        List<Integer> fellow = List.of();
        List<String> myCopLog = List.of();

        if (joined && me.alive) {
            if (phase == Phase.NIGHT && me.role != null) {
                switch (me.role) {
                    case MAFIA -> {
                        actionKind = "MAFIA_KILL";
                        Integer mp = mafiaPicks.get(seatOf(me));
                        myTarget = mp == null || mp < 0 ? -1 : mp + 1;
                    }
                    case POLICE -> { actionKind = "POLICE_CHECK"; myTarget = copTarget < 0 ? -1 : copTarget + 1; }
                    case DOCTOR -> { actionKind = "DOCTOR_SAVE"; myTarget = doctorTarget < 0 ? -1 : doctorTarget + 1; }
                    case CITIZEN -> {
                        actionKind = "CITIZEN_WATCH";
                        Integer cp = citizenPicks.get(seatOf(me));
                        myTarget = cp == null ? -1 : cp + 1;
                    }
                }
                if (!actionKind.equals("NONE"))
                    selectable = selectableSeats(me).stream().map(s -> s + 1).toList();
            } else if (phase == Phase.VOTE) {
                actionKind = "VOTE";
                Integer v = votes.get(seatOf(me));
                myTarget = v == null ? -1 : (v == -1 ? -1 : v + 1);
                selectable = aliveSeats().stream().filter(s -> s != seatOf(me)).map(s -> s + 1).toList();
            }
        }
        List<VoteView> mafiaPickTally = List.of();
        if (joined && me.role == Role.MAFIA) {
            fellow = new ArrayList<>();
            for (int i = 0; i < players.size(); i++)
                if (players.get(i).role == Role.MAFIA) fellow.add(i + 1);
            // 밤 동안 동료들의 실시간 지목 현황(마피아에게만)
            if (phase == Phase.NIGHT) {
                Map<Integer, Integer> counts = new LinkedHashMap<>();
                for (int t : mafiaPicks.values()) if (t >= 0) counts.merge(t, 1, Integer::sum);
                List<VoteView> mp = new ArrayList<>();
                counts.forEach((k, v) -> mp.add(new VoteView(k + 1, v)));
                mafiaPickTally = mp;
            }
        }
        if (joined && me.role == Role.POLICE) myCopLog = List.copyOf(copLog);

        List<VoteView> tally = new ArrayList<>();
        if (phase == Phase.VOTE || phase == Phase.EXECUTE) {
            Map<Integer, Integer> counts = new LinkedHashMap<>();
            for (int t : votes.values()) if (t != -1) counts.merge(t, 1, Integer::sum);
            counts.forEach((k, v) -> tally.add(new VoteView(k + 1, v)));
        }

        long mafiaAlive = players.stream().filter(p -> p.alive && p.role == Role.MAFIA).count();
        int totalMafia = (int) players.stream().filter(p -> p.role == Role.MAFIA).count();

        return new MafiaStateResponse(
                phase.name(),
                round,
                phaseEndsAt,
                now,
                clientId.equals(hostClientId),
                joined,
                joined ? mySeatIdx + 1 : 0,
                joined ? me.nick : null,
                joined && me.role != null ? me.role.name() : null,
                joined && me.role != null ? (me.role == Role.MAFIA ? "MAFIA" : "CITIZEN") : null,
                joined && me.alive,
                board,
                actionKind,
                selectable,
                myTarget,
                fellow,
                myCopLog,
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
                totalMafia,
                players.size(),
                mafiaPickTally,
                (int) skipVotes.stream().filter(s -> s < players.size() && players.get(s).alive).count(),
                joined && skipVotes.contains(mySeatIdx),
                List.copyOf(history),
                joined && myLogs.containsKey(mySeatIdx) ? List.copyOf(myLogs.get(mySeatIdx)) : List.of(),
                recentChat()
        );
    }

    /** 최근 채팅(최대 60줄) → 뷰. */
    private List<ChatView> recentChat() {
        int from = Math.max(0, chat.size() - 60);
        List<ChatView> out = new ArrayList<>();
        for (int i = from; i < chat.size(); i++) {
            ChatMsg c = chat.get(i);
            out.add(new ChatView(c.seat() + 1, c.nick(), c.text(), c.ai(), c.round()));
        }
        return out;
    }

    // =================== 유틸 ===================

    private void reset() {
        phase = null;
        phaseEndsAt = 0;
        round = 0;
        hostClientId = null;
        players.clear();
        clientSeats.clear();
        leftClients.clear();
        nightMs = 60_000; discussMs = 90_000; voteMs = 30_000;
        configMafiaCount = 0;
        revealOnDeath = true;
        copTarget = doctorTarget = lastDoctorTarget = -1;
        copLog.clear();
        copFindings.clear();
        skipVotes.clear();
        history.clear();
        myLogs.clear();
        mafiaPicks.clear();
        citizenPicks.clear();
        nightActed.clear();
        votes.clear();
        defenseMs = 20_000; finalVoteMs = 20_000;
        accusedSeat = -1; finalVotes.clear();
        nightMessage = null;
        nightDeadSeat = executedSeat = -1;
        winner = null;
        chat.clear();
        botCounter.set(0);
        botInFlight.clear();
        botNightAt.clear();
        botChatAt.clear();
        botChatCount.clear();
        botHeldChat.clear();
        botFinalAt.clear();
        botFinalInFlight.clear();
        botVoteAt.clear();
        botPacingRound = -1;
    }

    private void addPlayer(String clientId, String nick) {
        int seat = players.size();
        players.add(new Player(clientId, trimNick(nick)));
        clientSeats.put(clientId, seat);
    }

    /** 이 역할이 이번 밤 지목할 수 있는 좌석(0-based)들. */
    private List<Integer> selectableSeats(Player actor) {
        List<Integer> out = new ArrayList<>();
        int myS = seatOf(actor);
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            if (!p.alive) continue;
            switch (actor.role) {
                case MAFIA -> { if (p.role != Role.MAFIA) out.add(i); }        // 동료 제외
                case POLICE -> { if (i != myS) out.add(i); }                    // 자기 제외
                case DOCTOR -> { if (i != lastDoctorTarget) out.add(i); }       // 자기 보호 OK, 연속 보호만 금지
                case CITIZEN -> { if (i != myS) out.add(i); }                   // 위장 지목(자기 제외)
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

    private int to0(int seat1) { return seat1 <= 0 ? -1 : seat1 - 1; }   // 1-based → 0-based(-1 유지)
    private int seat1(int seat0) { return seat0 < 0 ? -1 : seat0 + 1; }  // 0-based → 1-based

    private static String trimNick(String nick) {
        String t = nick == null ? "" : nick.trim();
        if (t.isEmpty()) t = "익명";
        return t.length() > 16 ? t.substring(0, 16) : t;
    }

    private static int clampSec(Integer v, int min, int max, int def) {
        if (v == null) return def;
        return Math.max(min, Math.min(max, v));
    }
}
