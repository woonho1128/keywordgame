package com.wordplay.mafia;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.mafia.dto.MafiaStateResponse;
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

/**
 * 마피아(완전 자동·타이머) 단일 전역 방. 인메모리로 관리(DB 미사용).
 *
 * 진행은 타이머로 자동. 별도 스레드 없이, 폴링/행동이 들어올 때마다 tick()이
 * "지금 ≥ 페이즈 종료시각"이면 다음 페이즈로 넘긴다(lazy advance). 모두가
 * 1초마다 폴링하므로 사실상 실시간으로 흘러간다.
 */
public class MafiaService implements RoomGame {

    enum Phase { LOBBY, NIGHT, MORNING, DISCUSS, VOTE, EXECUTE, ENDED }
    enum Role { MAFIA, POLICE, DOCTOR, CITIZEN }

    private static final long MORNING_MS = 6_000;
    private static final long EXECUTE_MS = 6_000;

    private static final class Player {
        final String clientId;
        String nick;
        Role role;
        boolean alive = true;
        Player(String clientId, String nick) { this.clientId = clientId; this.nick = nick; }
    }

    // ---- 게임 상태 ----
    private Phase phase = null;              // null = 방 없음(NOT_STARTED)
    private long phaseEndsAt = 0;
    private long round = 0;
    private long lastActiveMs = System.currentTimeMillis();
    private String hostClientId = null;
    private final List<Player> players = new ArrayList<>();          // seat = index
    private final Map<String, Integer> clientSeats = new HashMap<>(); // clientId -> seat

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

    // 발표용 결과
    private String nightMessage = null;
    private int nightDeadSeat = -1;
    private int executedSeat = -1;
    private String winner = null;

    // =================== 명령 ===================

    /** 방 생성 + 방장 참가. */
    public synchronized MafiaStateResponse newGame(String clientId, NewMafiaRequest req) {
        reset();
        this.phase = Phase.LOBBY;
        this.hostClientId = clientId;
        this.nightMs = clampSec(req.nightSec(), 20, 180, 60) * 1000L;
        this.discussMs = clampSec(req.discussSec(), 15, 300, 90) * 1000L;
        this.voteMs = clampSec(req.voteSec(), 10, 120, 30) * 1000L;
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
    @Override public synchronized int playerCount() { return players.size(); }
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

    private void maybeAdvanceVote() {
        if (votes.size() >= aliveSeats().size()) advance();
    }

    private void advance() {
        switch (phase) {
            case NIGHT -> { resolveNight(); if (!checkWin()) startPhase(Phase.MORNING); }
            case MORNING -> { skipVotes.clear(); startPhase(Phase.DISCUSS); }
            case DISCUSS -> { votes.clear(); startPhase(Phase.VOTE); }
            case VOTE -> { resolveVote(); if (!checkWin()) startPhase(Phase.EXECUTE); }
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
        if (docSeat >= 0 && doctorTarget >= 0)
            myLog(docSeat).add(round + "일차 💉 보호: " + players.get(doctorTarget).nick);
        for (var e : mafiaPicks.entrySet())
            if (e.getValue() >= 0)
                myLog(e.getKey()).add(round + "일차 🔪 지목: " + players.get(e.getValue()).nick);
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

    private void resolveVote() {
        executedSeat = -1;
        Map<Integer, Integer> tally = new HashMap<>();
        for (int t : votes.values()) if (t != -1) tally.merge(t, 1, Integer::sum);
        // 개인 투표 기록(자기만 봄)
        for (var e : votes.entrySet())
            myLog(e.getKey()).add(round + "일차 🗳️ 투표: " + (e.getValue() < 0 ? "기권" : players.get(e.getValue()).nick));
        // 공개 집계
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
        int max = 0, top = -1;
        boolean tie = false;
        for (var e : tally.entrySet()) {
            if (e.getValue() > max) { max = e.getValue(); top = e.getKey(); tie = false; }
            else if (e.getValue() == max) tie = true;
        }
        if (top >= 0 && !tie && max > 0) {
            players.get(top).alive = false;
            executedSeat = top;
            history.add(round + "일차 ☀️ " + players.get(top).nick + "님 처형 (정체: " + roleKor(players.get(top).role) + ")");
        } else {
            history.add(round + "일차 ☀️ 처형 없음 (동표 또는 기권)");
        }
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
            board.add(new PlayerView(i + 1, p.nick, p.alive, shownRole, copResult));
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
                ended ? winner : null,
                (int) players.stream().filter(p -> p.alive).count(),
                totalMafia,
                players.size(),
                mafiaPickTally,
                (int) skipVotes.stream().filter(s -> s < players.size() && players.get(s).alive).count(),
                joined && skipVotes.contains(mySeatIdx),
                List.copyOf(history),
                joined && myLogs.containsKey(mySeatIdx) ? List.copyOf(myLogs.get(mySeatIdx)) : List.of()
        );
    }

    // =================== 유틸 ===================

    private void reset() {
        phase = null;
        phaseEndsAt = 0;
        round = 0;
        hostClientId = null;
        players.clear();
        clientSeats.clear();
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
        nightMessage = null;
        nightDeadSeat = executedSeat = -1;
        winner = null;
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
