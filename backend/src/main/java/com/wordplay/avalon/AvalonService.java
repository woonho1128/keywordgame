package com.wordplay.avalon;

import com.wordplay.avalon.dto.AvalonStateResponse;
import com.wordplay.avalon.dto.AvalonStateResponse.PlayerView;
import com.wordplay.avalon.dto.AvalonStateResponse.VoteView;
import com.wordplay.avalon.dto.NewAvalonRequest;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 레지스탕스: 아발론. 인메모리 단일 전역 방, 타이머/행동 기반 진행.
 *
 * 흐름: LOBBY → REVEAL(역할·지식 확인) → [TEAM_BUILD → TEAM_VOTE → (승인 시) QUEST] × 최대 5원정
 *      → 원정 3성공이면 ASSASSIN(암살자가 멀린 지목) → ENDED
 */
@Service
public class AvalonService {

    enum Phase { LOBBY, REVEAL, TEAM_BUILD, TEAM_VOTE, QUEST, ASSASSIN, ENDED }
    enum Role { MERLIN, PERCIVAL, SERVANT, ASSASSIN, MORGANA, MORDRED, OBERON, MINION }

    private static final long REVEAL_MS = 60_000, BUILD_MS = 120_000,
            VOTE_MS = 60_000, QUEST_MS = 60_000, ASSASSIN_MS = 60_000;

    // 원정 인원표 / 실패 필요 수 / 악 인원 (인원수 5~10)
    private static final int[][] TEAM = {
            {2, 3, 2, 3, 3}, {2, 3, 4, 3, 4}, {2, 3, 3, 4, 4},
            {3, 4, 4, 5, 5}, {3, 4, 4, 5, 5}, {3, 4, 4, 5, 5}};
    private static final int[] EVIL = {2, 2, 3, 3, 3, 4};

    private int teamSize(int pc, int q) { return TEAM[pc - 5][q]; }
    private int failsReq(int pc, int q) { return (pc >= 7 && q == 3) ? 2 : 1; }
    private int evilCount(int pc) { return EVIL[pc - 5]; }

    private static final class Player {
        final String clientId;
        String nick;
        Role role;
        Player(String clientId, String nick) { this.clientId = clientId; this.nick = nick; }
    }

    private Phase phase = null;
    private long phaseEndsAt = 0;
    private String hostClientId = null;
    private final List<Player> players = new ArrayList<>();
    private final Map<String, Integer> clientSeats = new HashMap<>();

    private Boolean cfgPM = null, cfgMordred = null, cfgOberon = null;

    private final Map<Integer, List<String>> knowledge = new HashMap<>();
    private int leaderSeat = 0;
    private int questIndex = 0;
    private final List<String> questResults = new ArrayList<>();
    private int successCount = 0, failCount = 0, rejectCount = 0;

    private final List<Integer> proposedTeam = new ArrayList<>();
    private final Map<Integer, Boolean> teamVotes = new HashMap<>();
    private final Map<Integer, Boolean> lastVotes = new HashMap<>();
    private String lastVoteResult = null;
    private final Map<Integer, Boolean> questCards = new HashMap<>();
    private int lastQuestFails = -1;
    private final Set<Integer> readySet = new HashSet<>();

    private int assassinTarget = -1;
    private int merlinSeat = -1;
    private String winner = null, winReason = null;

    // =================== 명령 ===================

    public synchronized AvalonStateResponse newGame(String clientId, NewAvalonRequest req) {
        reset();
        phase = Phase.LOBBY;
        hostClientId = clientId;
        cfgPM = req.includePercivalMorgana();
        cfgMordred = req.includeMordred();
        cfgOberon = req.includeOberon();
        addPlayer(clientId, req.nick());
        return me(clientId);
    }

    public synchronized AvalonStateResponse join(String clientId, String nick) {
        if (phase == null) throw bad("생성된 방이 없습니다");
        if (phase != Phase.LOBBY) throw bad("이미 진행 중이라 참가할 수 없습니다");
        if (!clientSeats.containsKey(clientId)) {
            if (players.size() >= 10) throw bad("정원(10명)이 찼습니다");
            addPlayer(clientId, nick);
        } else {
            players.get(clientSeats.get(clientId)).nick = trimNick(nick);
        }
        return me(clientId);
    }

    public synchronized AvalonStateResponse start(String clientId) {
        if (phase != Phase.LOBBY) throw bad("지금 시작할 수 없습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 시작할 수 있습니다");
        int n = players.size();
        if (n < 5) throw bad("최소 5명이 필요합니다");

        boolean pm = cfgPM == null || cfgPM;   // 기본 포함
        boolean mordred = cfgMordred != null && cfgMordred;
        boolean oberon = cfgOberon != null && cfgOberon;

        int evil = evilCount(n);
        List<Role> evilRoles = new ArrayList<>();
        evilRoles.add(Role.ASSASSIN);
        if (pm && evilRoles.size() < evil) evilRoles.add(Role.MORGANA);
        if (mordred && evilRoles.size() < evil) evilRoles.add(Role.MORDRED);
        if (oberon && evilRoles.size() < evil) evilRoles.add(Role.OBERON);
        while (evilRoles.size() < evil) evilRoles.add(Role.MINION);

        List<Role> goodRoles = new ArrayList<>();
        goodRoles.add(Role.MERLIN);
        if (pm) goodRoles.add(Role.PERCIVAL);
        int good = n - evil;
        while (goodRoles.size() < good) goodRoles.add(Role.SERVANT);

        List<Role> all = new ArrayList<>();
        all.addAll(evilRoles);
        all.addAll(goodRoles);
        Collections.shuffle(all);
        for (int i = 0; i < n; i++) players.get(i).role = all.get(i);

        computeKnowledge();
        leaderSeat = ThreadLocalRandom.current().nextInt(n);
        questIndex = 0;
        questResults.clear();
        successCount = failCount = rejectCount = 0;
        lastQuestFails = -1;
        lastVoteResult = null;
        readySet.clear();
        startPhase(Phase.REVEAL);
        return me(clientId);
    }

    /** REVEAL 확인. 전원 확인하면 첫 원정 편성으로. */
    public synchronized AvalonStateResponse ready(String clientId) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.REVEAL) return me(clientId);
        readySet.add(seatOf(me));
        if (readySet.size() >= players.size()) startTeamBuild();
        return me(clientId);
    }

    /** 리더가 원정대 제안. */
    public synchronized AvalonStateResponse propose(String clientId, List<Integer> team) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.TEAM_BUILD) throw bad("지금은 원정대 편성 단계가 아닙니다");
        if (seatOf(me) != leaderSeat) throw bad("리더만 원정대를 제안할 수 있습니다");
        int need = teamSize(players.size(), questIndex);
        Set<Integer> picked = new HashSet<>();
        if (team != null) for (int s : team) picked.add(s - 1);
        if (picked.size() != need) throw bad("원정대는 " + need + "명이어야 합니다");
        for (int s : picked) if (s < 0 || s >= players.size()) throw bad("잘못된 대상입니다");
        List<Integer> sorted = new ArrayList<>(picked);
        Collections.sort(sorted);
        proposedTeam.clear();
        proposedTeam.addAll(sorted);
        startTeamVote();
        return me(clientId);
    }

    /** 찬반 투표. */
    public synchronized AvalonStateResponse vote(String clientId, boolean approve) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.TEAM_VOTE) throw bad("지금은 투표 단계가 아닙니다");
        teamVotes.put(seatOf(me), approve);
        if (teamVotes.size() >= players.size()) resolveVote();
        return me(clientId);
    }

    /** 원정 카드(성공/실패). 선은 항상 성공으로 강제. */
    public synchronized AvalonStateResponse quest(String clientId, boolean success) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.QUEST) throw bad("지금은 원정 단계가 아닙니다");
        int s = seatOf(me);
        if (!proposedTeam.contains(s)) throw bad("원정대원이 아닙니다");
        boolean card = team(me.role).equals("GOOD") ? true : success; // 선은 무조건 성공
        questCards.put(s, card);
        if (questCards.size() >= proposedTeam.size()) resolveQuest();
        return me(clientId);
    }

    /** 암살자의 멀린 지목. */
    public synchronized AvalonStateResponse assassinate(String clientId, int target) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.ASSASSIN) throw bad("지금은 암살 단계가 아닙니다");
        if (me.role != Role.ASSASSIN) throw bad("암살자만 지목할 수 있습니다");
        int t = target - 1;
        if (t < 0 || t >= players.size()) throw bad("잘못된 대상입니다");
        resolveAssassin(t);
        return me(clientId);
    }

    public synchronized AvalonStateResponse resetGame() {
        reset();
        return AvalonStateResponse.notStarted(System.currentTimeMillis());
    }

    public synchronized AvalonStateResponse me(String clientId) {
        tick();
        return buildResponse(clientId);
    }

    // =================== 타이머/진행 ===================

    private void tick() {
        long now = System.currentTimeMillis();
        int guard = 0;
        while (phase != null && phase != Phase.LOBBY && phase != Phase.ENDED
                && phaseEndsAt > 0 && now >= phaseEndsAt && guard++ < 30) {
            switch (phase) {
                case REVEAL -> startTeamBuild();
                case TEAM_BUILD -> { autoPropose(); startTeamVote(); }
                case TEAM_VOTE -> resolveVote();
                case QUEST -> resolveQuest();
                case ASSASSIN -> resolveAssassin(randomGoodSeat());
                default -> { }
            }
        }
    }

    private void startPhase(Phase p) {
        phase = p;
        long now = System.currentTimeMillis();
        phaseEndsAt = switch (p) {
            case REVEAL -> now + REVEAL_MS;
            case TEAM_BUILD -> now + BUILD_MS;
            case TEAM_VOTE -> now + VOTE_MS;
            case QUEST -> now + QUEST_MS;
            case ASSASSIN -> now + ASSASSIN_MS;
            default -> 0;
        };
    }

    private void startTeamBuild() {
        proposedTeam.clear();
        teamVotes.clear();
        startPhase(Phase.TEAM_BUILD);
    }

    private void startTeamVote() {
        teamVotes.clear();
        startPhase(Phase.TEAM_VOTE);
    }

    private void startQuest() {
        questCards.clear();
        startPhase(Phase.QUEST);
    }

    private void autoPropose() {
        // 리더 미제안 시 유효한 팀 자동 구성(리더 우선 포함)
        int need = teamSize(players.size(), questIndex);
        List<Integer> seats = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) seats.add(i);
        Collections.shuffle(seats);
        seats.remove((Integer) leaderSeat);
        seats.add(0, leaderSeat);
        proposedTeam.clear();
        for (int i = 0; i < need; i++) proposedTeam.add(seats.get(i));
        Collections.sort(proposedTeam);
    }

    private void resolveVote() {
        int approve = 0;
        for (boolean b : teamVotes.values()) if (b) approve++;
        lastVotes.clear();
        lastVotes.putAll(teamVotes);
        boolean approved = approve * 2 > players.size();
        if (approved) {
            lastVoteResult = "APPROVED";
            rejectCount = 0;
            startQuest();
        } else {
            lastVoteResult = "REJECTED";
            rejectCount++;
            if (rejectCount >= 5) {
                endGame("EVIL", "원정대가 5번 연속 거부되었습니다");
            } else {
                leaderSeat = (leaderSeat + 1) % players.size();
                startTeamBuild();
            }
        }
    }

    private void resolveQuest() {
        int fails = 0;
        for (int s : proposedTeam) {
            Boolean c = questCards.get(s);
            if (c != null && !c) fails++; // 미제출은 성공 처리
        }
        int req = failsReq(players.size(), questIndex);
        boolean failed = fails >= req;
        questResults.add(failed ? "FAIL" : "SUCCESS");
        if (failed) failCount++; else successCount++;
        lastQuestFails = fails;
        questIndex++;

        if (failCount >= 3) { endGame("EVIL", "원정 3회 실패"); return; }
        if (successCount >= 3) {
            if (assassinSeat() >= 0) startPhase(Phase.ASSASSIN);
            else endGame("GOOD", "원정 3회 성공");
            return;
        }
        leaderSeat = (leaderSeat + 1) % players.size();
        startTeamBuild();
    }

    private void resolveAssassin(int target) {
        assassinTarget = target;
        if (target >= 0 && players.get(target).role == Role.MERLIN)
            endGame("EVIL", "암살자가 멀린을 처치했습니다");
        else
            endGame("GOOD", "암살자가 멀린을 찾지 못했습니다");
    }

    private void endGame(String w, String reason) {
        winner = w;
        winReason = reason;
        merlinSeat = merlinSeat();
        phase = Phase.ENDED;
        phaseEndsAt = 0;
    }

    // =================== 지식 계산 ===================

    private void computeKnowledge() {
        knowledge.clear();
        for (int i = 0; i < players.size(); i++) {
            List<String> lines = new ArrayList<>();
            Role r = players.get(i).role;
            switch (r) {
                case MERLIN -> lines.add("악의 하수인: " + names(evilSeatsExcept(Role.MORDRED)) + " (모드레드는 안 보임)");
                case PERCIVAL -> lines.add("멀린 후보: " + names(shuffledMerlinMorgana()) + " (둘 중 하나가 진짜 멀린)");
                case OBERON -> lines.add("당신은 오베론입니다. 동료 악을 알 수 없습니다.");
                case SERVANT -> lines.add("당신은 충성스러운 신하입니다. 아는 정보가 없습니다.");
                case ASSASSIN, MORGANA, MORDRED, MINION -> {
                    List<Integer> mates = new ArrayList<>();
                    for (int j = 0; j < players.size(); j++) {
                        if (j == i) continue;
                        Role jr = players.get(j).role;
                        if (isEvil(jr) && jr != Role.OBERON) mates.add(j);
                    }
                    lines.add("동료 악: " + (mates.isEmpty() ? "(없음)" : names(mates)) + " (오베론은 안 보임)");
                }
            }
            knowledge.put(i, lines);
        }
    }

    private List<Integer> evilSeatsExcept(Role except) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < players.size(); i++)
            if (isEvil(players.get(i).role) && players.get(i).role != except) out.add(i);
        return out;
    }

    private List<Integer> shuffledMerlinMorgana() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < players.size(); i++)
            if (players.get(i).role == Role.MERLIN || players.get(i).role == Role.MORGANA) out.add(i);
        Collections.shuffle(out);
        return out;
    }

    private String names(List<Integer> seats) {
        List<String> ns = new ArrayList<>();
        for (int s : seats) ns.add(players.get(s).nick);
        return String.join(", ", ns);
    }

    // =================== 응답 빌드 ===================

    private AvalonStateResponse buildResponse(String clientId) {
        long now = System.currentTimeMillis();
        if (phase == null) return AvalonStateResponse.notStarted(now);

        boolean ended = phase == Phase.ENDED;
        Integer mySeat = clientSeats.get(clientId);
        Player me = mySeat == null ? null : players.get(mySeat);
        boolean joined = me != null;
        int pc = players.size();

        List<PlayerView> board = new ArrayList<>();
        for (int i = 0; i < pc; i++) {
            String role = ended && players.get(i).role != null ? players.get(i).role.name() : null;
            board.add(new PlayerView(i + 1, players.get(i).nick, role));
        }

        List<Integer> teamSizes = new ArrayList<>(), fails = new ArrayList<>();
        if (pc >= 5 && pc <= 10) {
            for (int q = 0; q < 5; q++) { teamSizes.add(teamSize(pc, q)); fails.add(failsReq(pc, q)); }
        }

        List<Integer> proposed1 = new ArrayList<>();
        for (int s : proposedTeam) proposed1.add(s + 1);

        List<VoteView> lastVoteView = new ArrayList<>();
        for (var e : new TreeMap<>(lastVotes).entrySet())
            lastVoteView.add(new VoteView(e.getKey() + 1, e.getValue()));

        String myVote = null;
        if (joined && teamVotes.containsKey(mySeat)) myVote = teamVotes.get(mySeat) ? "APPROVE" : "REJECT";
        String myCard = null;
        if (joined && questCards.containsKey(mySeat)) myCard = questCards.get(mySeat) ? "SUCCESS" : "FAIL";

        return new AvalonStateResponse(
                phase.name(),
                phaseEndsAt,
                now,
                clientId.equals(hostClientId),
                joined,
                joined ? mySeat + 1 : 0,
                joined ? me.nick : null,
                joined && me.role != null ? me.role.name() : null,
                joined && me.role != null ? team(me.role) : null,
                joined ? knowledge.getOrDefault(mySeat, List.of()) : List.of(),
                board,
                leaderSeat + 1,
                joined && phase == Phase.TEAM_BUILD && mySeat == leaderSeat,
                Math.min(questIndex + 1, 5),
                teamSizes,
                fails,
                List.copyOf(questResults),
                successCount,
                failCount,
                rejectCount,
                proposed1,
                (pc >= 5 && pc <= 10) ? teamSize(pc, Math.min(questIndex, 4)) : 0,
                joined && proposedTeam.contains(mySeat),
                joined && readySet.contains(mySeat),
                readySet.size(),
                myVote,
                teamVotes.size(),
                lastVoteView,
                lastVoteResult,
                myCard,
                questCards.size(),
                lastQuestFails,
                joined && me.role == Role.ASSASSIN,
                assassinTarget < 0 ? -1 : assassinTarget + 1,
                merlinSeat < 0 ? -1 : merlinSeat + 1,
                ended ? winner : null,
                ended ? winReason : null,
                pc
        );
    }

    // =================== 유틸 ===================

    private boolean isEvil(Role r) {
        return r == Role.ASSASSIN || r == Role.MORGANA || r == Role.MORDRED
                || r == Role.OBERON || r == Role.MINION;
    }

    private String team(Role r) { return isEvil(r) ? "EVIL" : "GOOD"; }

    private int assassinSeat() {
        for (int i = 0; i < players.size(); i++) if (players.get(i).role == Role.ASSASSIN) return i;
        return -1;
    }

    private int merlinSeat() {
        for (int i = 0; i < players.size(); i++) if (players.get(i).role == Role.MERLIN) return i;
        return -1;
    }

    private int randomGoodSeat() {
        List<Integer> good = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) if (!isEvil(players.get(i).role)) good.add(i);
        return good.isEmpty() ? 0 : good.get(ThreadLocalRandom.current().nextInt(good.size()));
    }

    private int seatOf(Player p) { return players.indexOf(p); }

    private Player requirePlayer(String clientId) {
        Integer s = clientSeats.get(clientId);
        if (s == null) throw bad("참가하지 않은 기기입니다");
        return players.get(s);
    }

    private void addPlayer(String clientId, String nick) {
        clientSeats.put(clientId, players.size());
        players.add(new Player(clientId, trimNick(nick)));
    }

    private void reset() {
        phase = null;
        phaseEndsAt = 0;
        hostClientId = null;
        players.clear();
        clientSeats.clear();
        cfgPM = cfgMordred = cfgOberon = null;
        knowledge.clear();
        leaderSeat = 0;
        questIndex = 0;
        questResults.clear();
        successCount = failCount = rejectCount = 0;
        proposedTeam.clear();
        teamVotes.clear();
        lastVotes.clear();
        lastVoteResult = null;
        questCards.clear();
        lastQuestFails = -1;
        readySet.clear();
        assassinTarget = -1;
        merlinSeat = -1;
        winner = winReason = null;
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
