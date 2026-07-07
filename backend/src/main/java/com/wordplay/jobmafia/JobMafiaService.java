package com.wordplay.jobmafia;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.jobmafia.dto.JobMafiaStateResponse;
import com.wordplay.jobmafia.dto.JobMafiaStateResponse.PlayerView;
import com.wordplay.jobmafia.dto.JobMafiaStateResponse.VoteView;
import com.wordplay.jobmafia.dto.NewJobMafiaRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
@Service
public class JobMafiaService {

    enum Phase { LOBBY, NIGHT, MORNING, DISCUSS, VOTE, EXECUTE, ENDED }
    enum Role { CITIZEN, POLICE, DOCTOR, PSYCHO, MAFIA, ATTENTION }

    private static final long MORNING_MS = 6_000;
    private static final long EXECUTE_MS = 6_000;

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
    private String hostClientId = null;
    private final List<Player> players = new ArrayList<>();
    private final Map<String, Integer> clientSeats = new HashMap<>();

    // 설정
    private long nightMs = 60_000, discussMs = 90_000, voteMs = 30_000;
    private int cfgMafia = 0;                 // 0 = 자동
    private Boolean cfgPsycho = null, cfgAttention = null; // null = 자동
    private boolean revealOnDeath = true;

    // 밤 상태
    private final Map<Integer, Integer> nightTargetBySeat = new HashMap<>(); // 각자 이번 밤 지목(표시용)
    private int doctorTarget = -1;            // 진짜 의사의 보호 대상(효과 있음)
    private final List<String> copLog = new ArrayList<>();       // 진짜 경찰 조사 기록
    private final List<String> psychoCopLog = new ArrayList<>(); // 가짜 경찰(정신병자) 기록
    private final Set<Integer> nightActed = new HashSet<>();

    // 정신병자
    private int psychoSeat = -1;
    private Role psychoFakeRole = Role.POLICE;

    // 투표
    private final Map<Integer, Integer> votes = new HashMap<>();

    // 결과
    private String nightMessage = null;
    private int nightDeadSeat = -1;
    private int executedSeat = -1;
    private String winner = null;

    // =================== 명령 ===================

    public synchronized JobMafiaStateResponse newGame(String clientId, NewJobMafiaRequest req) {
        reset();
        phase = Phase.LOBBY;
        hostClientId = clientId;
        nightMs = clampSec(req.nightSec(), 20, 180, 60) * 1000L;
        discussMs = clampSec(req.discussSec(), 15, 300, 90) * 1000L;
        voteMs = clampSec(req.voteSec(), 10, 120, 30) * 1000L;
        cfgMafia = req.mafiaCount() == null ? 0 : Math.max(0, req.mafiaCount());
        cfgPsycho = req.includePsycho();
        cfgAttention = req.includeAttention();
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

        int mafia = cfgMafia > 0 ? cfgMafia : Math.max(1, n / 4);
        boolean psycho = cfgPsycho == null ? n >= 6 : cfgPsycho;
        boolean attention = cfgAttention == null ? n >= 5 : cfgAttention;
        int specials = mafia + 2 + (psycho ? 1 : 0) + (attention ? 1 : 0); // +경찰,의사
        if (specials > n)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "직업 구성이 인원보다 많습니다. 마피아 수나 특수직업을 줄이세요");

        List<Role> roles = new ArrayList<>();
        for (int i = 0; i < mafia; i++) roles.add(Role.MAFIA);
        roles.add(Role.POLICE);
        roles.add(Role.DOCTOR);
        if (psycho) roles.add(Role.PSYCHO);
        if (attention) roles.add(Role.ATTENTION);
        while (roles.size() < n) roles.add(Role.CITIZEN);
        Collections.shuffle(roles);

        psychoSeat = -1;
        for (int i = 0; i < n; i++) {
            players.get(i).role = roles.get(i);
            players.get(i).alive = true;
            if (roles.get(i) == Role.PSYCHO) psychoSeat = i;
        }
        if (psychoSeat >= 0) {
            // 정신병자에게 보일 가짜 직업(경찰/의사 중 랜덤)
            psychoFakeRole = ThreadLocalRandom.current().nextBoolean() ? Role.POLICE : Role.DOCTOR;
        }

        round = 1;
        copLog.clear();
        psychoCopLog.clear();
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

        nightTargetBySeat.put(seat, t);
        switch (acting) {
            case POLICE -> {
                boolean isMafia = players.get(t).role == Role.MAFIA;
                if (me.role == Role.PSYCHO) {
                    // 가짜 경찰: 결과가 랜덤(효과 없음)
                    boolean fake = ThreadLocalRandom.current().nextBoolean();
                    psychoCopLog.add(round + "일차: " + players.get(t).nick + " → " + (fake ? "마피아 O" : "마피아 X"));
                } else {
                    copLog.add(round + "일차: " + players.get(t).nick + " → " + (isMafia ? "마피아 O" : "마피아 X"));
                }
            }
            case DOCTOR -> {
                if (me.role != Role.PSYCHO) doctorTarget = t; // 가짜 의사는 효과 없음
            }
            default -> { } // 마피아/시민/관종은 지목만 기록(마피아 결과는 다수결로 판정)
        }
        nightActed.add(seat);
        maybeAdvanceNight();
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

    public synchronized JobMafiaStateResponse resetGame() {
        reset();
        return JobMafiaStateResponse.notStarted(System.currentTimeMillis());
    }

    public synchronized JobMafiaStateResponse me(String clientId) {
        tick();
        return buildResponse(clientId);
    }

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
                resolveVote();
                if (executedSeat >= 0 && players.get(executedSeat).role == Role.ATTENTION) {
                    winner = "NEUTRAL"; phase = Phase.ENDED; phaseEndsAt = 0; // 관종 처형 → 관종 승
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
            case EXECUTE -> now + EXECUTE_MS;
            default -> 0;
        };
    }

    private void prepareNight() {
        nightTargetBySeat.clear();
        doctorTarget = -1;
        nightActed.clear();
    }

    private void resolveNight() {
        nightDeadSeat = -1;
        int mafiaTarget = mafiaPlurality();
        if (mafiaTarget >= 0 && mafiaTarget != doctorTarget && players.get(mafiaTarget).alive) {
            players.get(mafiaTarget).alive = false;
            nightDeadSeat = mafiaTarget;
            nightMessage = players.get(mafiaTarget).nick + "님이 밤 사이 사망했습니다.";
        } else {
            nightMessage = "평화로운 밤이었습니다. 아무도 죽지 않았습니다.";
        }
    }

    /** 살아있는 마피아들의 지목 다수결(동수는 낮은 좌석). 없으면 -1. */
    private int mafiaPlurality() {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).alive && players.get(i).role == Role.MAFIA) {
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

    private void resolveVote() {
        executedSeat = -1;
        Map<Integer, Integer> tally = new HashMap<>();
        for (int t : votes.values()) if (t != -1) tally.merge(t, 1, Integer::sum);
        int max = 0, top = -1;
        boolean tie = false;
        for (var e : tally.entrySet()) {
            if (e.getValue() > max) { max = e.getValue(); top = e.getKey(); tie = false; }
            else if (e.getValue() == max) tie = true;
        }
        if (top >= 0 && !tie && max > 0) {
            players.get(top).alive = false;
            executedSeat = top;
        }
    }

    private boolean checkWin() {
        long mafiaAlive = players.stream().filter(p -> p.alive && p.role == Role.MAFIA).count();
        long nonMafiaAlive = players.stream().filter(p -> p.alive && p.role != Role.MAFIA).count();
        if (mafiaAlive == 0) { winner = "CITIZEN"; phase = Phase.ENDED; phaseEndsAt = 0; return true; }
        if (mafiaAlive >= nonMafiaAlive) { winner = "MAFIA"; phase = Phase.ENDED; phaseEndsAt = 0; return true; }
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
            if (p.role != null && (ended || (revealOnDeath && !p.alive))) shownRole = p.role.name(); // 실제 정체
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
                    case MAFIA -> "MAFIA_KILL";
                    case POLICE -> "POLICE_CHECK";
                    case DOCTOR -> "DOCTOR_SAVE";
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

        if (joined && me.role == Role.MAFIA) {
            fellow = new ArrayList<>();
            for (int i = 0; i < players.size(); i++)
                if (players.get(i).role == Role.MAFIA) fellow.add(i + 1);
            if (phase == Phase.NIGHT) {
                Map<Integer, Integer> counts = new LinkedHashMap<>();
                for (int i = 0; i < players.size(); i++) {
                    if (players.get(i).alive && players.get(i).role == Role.MAFIA) {
                        Integer t = nightTargetBySeat.get(i);
                        if (t != null && t >= 0) counts.merge(t, 1, Integer::sum);
                    }
                }
                List<VoteView> mp = new ArrayList<>();
                counts.forEach((k, v) -> mp.add(new VoteView(k + 1, v)));
                mafiaPickTally = mp;
            }
        }
        // 경찰 조사 기록(진짜 경찰은 진짜, 정신병자는 가짜 기록)
        if (joined && me.role == Role.POLICE) myCopLog = List.copyOf(copLog);
        else if (joined && me.role == Role.PSYCHO && psychoFakeRole == Role.POLICE) myCopLog = List.copyOf(psychoCopLog);

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
                selectable,
                myTarget,
                fellow,
                myCopLog,
                mafiaPickTally,
                (phase == Phase.MORNING || phase == Phase.DISCUSS || phase == Phase.VOTE || ended) ? nightMessage : null,
                (phase == Phase.MORNING) ? seat1(nightDeadSeat) : -1,
                (phase == Phase.EXECUTE || ended) ? seat1(executedSeat) : -1,
                tally,
                ended ? winner : null,
                (int) players.stream().filter(p -> p.alive).count(),
                players.size()
        );
    }

    // =================== 유틸 ===================

    /** 정신병자는 가짜 직업으로 취급(본인 화면·행동). 그 외는 실제 직업. */
    private Role effectiveRole(Player p) {
        return p.role == Role.PSYCHO ? psychoFakeRole : p.role;
    }

    private String teamOf(Role r) {
        return switch (r) {
            case MAFIA -> "MAFIA";
            case ATTENTION -> "NEUTRAL";
            default -> "CITIZEN";
        };
    }

    /** effective role 기준, 이번 밤 지목 가능한 좌석(0-based). */
    private List<Integer> selectableSeats(int mySeat, Role acting) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            if (!players.get(i).alive) continue;
            switch (acting) {
                case MAFIA -> { if (players.get(i).role != Role.MAFIA) out.add(i); }
                case DOCTOR -> out.add(i);            // 자기 보호 허용
                default -> { if (i != mySeat) out.add(i); } // 경찰/시민/관종: 자기 제외
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
        nightMs = 60_000; discussMs = 90_000; voteMs = 30_000;
        cfgMafia = 0; cfgPsycho = null; cfgAttention = null;
        revealOnDeath = true;
        nightTargetBySeat.clear();
        doctorTarget = -1;
        copLog.clear();
        psychoCopLog.clear();
        nightActed.clear();
        psychoSeat = -1;
        psychoFakeRole = Role.POLICE;
        votes.clear();
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
}
