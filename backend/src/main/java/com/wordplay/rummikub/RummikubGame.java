package com.wordplay.rummikub;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.rummikub.dto.RummikubStateResponse;
import com.wordplay.rummikub.dto.RummikubStateResponse.PlayerView;
import com.wordplay.rummikub.dto.RummikubStateResponse.TileView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 루미큐브 한 방(인메모리). 타일 106개, 2~4인, 그룹/런 세트, 첫 등록 30점, 조커 지원.
 * 턴은 "테이블 전체 제안 제출 → 서버가 타일 보존·세트 유효성 검증" 방식(재배치까지 지원).
 */
public class RummikubGame implements RoomGame {

    enum Phase { LOBBY, PLAYING, ENDED }

    /** 타일: joker면 color=null, number=0. */
    record Tile(int id, String color, int number, boolean joker) {}

    private static final String[] COLORS = {"RED", "BLUE", "BLACK", "ORANGE"};
    private static final Tile[] TILES = buildTiles(); // id → 타일 (0..105)

    private static Tile[] buildTiles() {
        Tile[] t = new Tile[106];
        int id = 0;
        for (int copy = 0; copy < 2; copy++)
            for (String c : COLORS)
                for (int n = 1; n <= 13; n++)
                    t[id] = new Tile(id++, c, n, false);
        t[104] = new Tile(104, null, 0, true);
        t[105] = new Tile(105, null, 0, true);
        return t;
    }

    private static final class Player {
        final String clientId;
        String nick;
        boolean ai = false;
        String aiLevel = "NORMAL"; // EASY / NORMAL / HARD
        final List<Integer> rack = new ArrayList<>();
        boolean melded = false;
        Player(String clientId, String nick) { this.clientId = clientId; this.nick = nick; }
    }

    private int aiCounter = 0;

    private Phase phase = null;
    private long lastActiveMs = System.currentTimeMillis();
    private String hostClientId = null;
    private final List<Player> players = new ArrayList<>();
    private final Map<String, Integer> clientSeats = new HashMap<>();
    private final Set<String> leftClients = new HashSet<>();

    private static final long DEFAULT_TURN_MS = 60_000L; // 차례 제한시간 기본 60초
    private long turnMs = DEFAULT_TURN_MS;                // 방 생성 시 설정 가능

    private final List<List<Integer>> table = new ArrayList<>();
    private final List<Integer> drawPile = new ArrayList<>();
    private int currentSeat = 0;
    private int winnerSeat = -1;
    private String lastAction = null;
    private long turnDeadlineMs = 0;

    // =================== 명령 ===================

    public synchronized RummikubStateResponse newGame(String clientId, String nick) {
        return newGame(clientId, nick, null);
    }

    public synchronized RummikubStateResponse newGame(String clientId, String nick, Integer turnSec) {
        reset();
        phase = Phase.LOBBY;
        hostClientId = clientId;
        this.turnMs = (turnSec == null ? 60 : Math.max(30, Math.min(300, turnSec))) * 1000L;
        addPlayer(clientId, nick);
        return me(clientId);
    }

    public synchronized RummikubStateResponse join(String clientId, String nick) {
        if (phase == null) throw bad("생성된 방이 없습니다");
        if (phase != Phase.LOBBY) throw bad("이미 진행 중이라 참가할 수 없습니다");
        if (!clientSeats.containsKey(clientId)) {
            if (players.size() >= 4) throw bad("정원(4명)이 찼습니다");
            addPlayer(clientId, nick);
        } else {
            players.get(clientSeats.get(clientId)).nick = trimNick(nick);
        }
        return me(clientId);
    }

    public synchronized RummikubStateResponse start(String clientId) {
        if (phase != Phase.LOBBY) throw bad("지금 시작할 수 없습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 시작할 수 있습니다");
        if (players.size() < 2) throw bad("최소 2명이 필요합니다");

        List<Integer> bag = new ArrayList<>();
        for (int i = 0; i < 106; i++) bag.add(i);
        Collections.shuffle(bag);
        int idx = 0;
        for (Player p : players) {
            p.rack.clear();
            p.melded = false;
            for (int k = 0; k < 14; k++) p.rack.add(bag.get(idx++));
            sortRack(p);
        }
        drawPile.clear();
        drawPile.addAll(bag.subList(idx, bag.size()));
        table.clear();
        currentSeat = 0;
        winnerSeat = -1;
        lastAction = players.get(0).nick + "님의 차례입니다.";
        phase = Phase.PLAYING;
        turnDeadlineMs = System.currentTimeMillis() + turnMs;
        return me(clientId);
    }

    /** 턴 제출: 제안 테이블 전체. */
    public synchronized RummikubStateResponse play(String clientId, List<List<Integer>> proposed) {
        Player me = requirePlayer(clientId);
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        if (seatOf(me) != currentSeat) throw bad("당신의 차례가 아닙니다");
        applyPlay(me, proposed);
        return me(clientId);
    }

    private void applyPlay(Player me, List<List<Integer>> proposed) {
        if (proposed == null) proposed = List.of();

        // 1) 타일 id 유효성 + 중복 없음
        List<Integer> flat = new ArrayList<>();
        for (List<Integer> set : proposed) {
            if (set != null) flat.addAll(set);
        }
        Set<Integer> newIds = new HashSet<>();
        for (int id : flat) {
            if (id < 0 || id >= 106) throw bad("잘못된 타일입니다");
            if (!newIds.add(id)) throw bad("타일이 중복되었습니다");
        }

        // 2) 보존: 기존 테이블 타일은 모두 유지, 추가분은 내 랙에서만
        Set<Integer> oldIds = new HashSet<>();
        for (List<Integer> s : table) oldIds.addAll(s);
        if (!newIds.containsAll(oldIds)) throw bad("테이블 위 타일을 가져갈 수 없습니다");
        Set<Integer> added = new HashSet<>(newIds);
        added.removeAll(oldIds);
        Set<Integer> rackSet = new HashSet<>(me.rack);
        if (!rackSet.containsAll(added)) throw bad("내 타일이 아닌 것을 놓았습니다");
        if (added.isEmpty()) throw bad("최소 한 개 이상 내려놓아야 합니다. (없으면 '가져오기')");

        // 3) 모든 세트가 유효한 그룹/런
        for (List<Integer> set : proposed) {
            if (set == null || set.size() < 3) throw bad("세트는 3개 이상이어야 합니다");
            if (setValue(set) < 0) throw bad("유효하지 않은 세트가 있습니다");
        }

        // 4) 첫 등록(이니셜 멜드): 30점 이상, 기존 세트 변형 금지
        if (!me.melded) {
            // 기존 세트는 그대로 존재해야 함
            for (List<Integer> oldSet : table) {
                if (proposed.stream().noneMatch(s -> new HashSet<>(s).equals(new HashSet<>(oldSet))))
                    throw bad("첫 등록 전에는 테이블을 바꿀 수 없습니다");
            }
            int meldSum = 0;
            for (List<Integer> set : proposed) {
                if (added.containsAll(set)) meldSum += setValue(set); // 내 타일로만 만든 새 세트
            }
            if (meldSum < 30) throw bad("첫 등록은 30점 이상이어야 합니다 (현재 " + meldSum + "점)");
        }

        // 커밋 (세트는 보기 좋게 정규화: 런=숫자 오름차순, 그룹=색 순서)
        me.rack.removeAll(added);
        table.clear();
        for (List<Integer> s : proposed) table.add(canonicalize(s));
        me.melded = true;
        if (me.rack.isEmpty()) {
            winnerSeat = seatOf(me);
            phase = Phase.ENDED;
            lastAction = me.nick + "님이 승리했습니다!";
        } else {
            nextTurn();
            lastAction = me.nick + "님이 타일을 내려놨습니다.";
        }
    }

    /** 더미에서 1개 가져오고 턴 종료. */
    public synchronized RummikubStateResponse draw(String clientId) {
        Player me = requirePlayer(clientId);
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        if (seatOf(me) != currentSeat) throw bad("당신의 차례가 아닙니다");
        applyDraw(me);
        return me(clientId);
    }

    private void applyDraw(Player me) {
        if (!drawPile.isEmpty()) {
            me.rack.add(drawPile.remove(drawPile.size() - 1));
            sortRack(me);
            lastAction = me.nick + "님이 타일을 가져왔습니다.";
        } else {
            lastAction = "더미가 비어 " + me.nick + "님이 턴을 넘겼습니다.";
        }
        nextTurn();
    }

    public synchronized RummikubStateResponse resetGame() {
        reset();
        return RummikubStateResponse.notStarted(System.currentTimeMillis());
    }

    public synchronized RummikubStateResponse me(String clientId) {
        lastActiveMs = System.currentTimeMillis();
        tickTimeout();
        advanceAis();
        return buildResponse(clientId);
    }

    /** 사람 차례가 제한시간(60초)을 넘기면 자동으로 '가져오기' 처리. AI 차례는 advanceAis가 담당. */
    private void tickTimeout() {
        if (phase != Phase.PLAYING) return;
        int guard = 0;
        while (phase == Phase.PLAYING && !players.get(currentSeat).ai
                && turnDeadlineMs > 0 && System.currentTimeMillis() >= turnDeadlineMs && guard++ < 20) {
            Player p = players.get(currentSeat);
            applyDraw(p); // nextTurn에서 새 데드라인이 설정됨
            lastAction = p.nick + "님이 시간초과로 타일을 가져왔습니다.";
        }
    }

    /** AI 플레이어 추가/제거(방장, 대기방). */
    public synchronized RummikubStateResponse addAi(String clientId) {
        return addAi(clientId, "NORMAL");
    }

    public synchronized RummikubStateResponse addAi(String clientId, String level) {
        if (phase != Phase.LOBBY) throw bad("대기방에서만 추가할 수 있습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 추가할 수 있습니다");
        if (players.size() >= 4) throw bad("정원(4명)이 찼습니다");
        String lvl = normalizeLevel(level);
        aiCounter++;
        Player p = new Player("AI#" + aiCounter, "🤖 봇" + aiCounter + "(" + levelLabel(lvl) + ")");
        p.ai = true;
        p.aiLevel = lvl;
        clientSeats.put(p.clientId, players.size());
        players.add(p);
        return me(clientId);
    }

    private static String normalizeLevel(String level) {
        if (level == null) return "NORMAL";
        String u = level.trim().toUpperCase();
        return switch (u) { case "EASY", "NORMAL", "HARD" -> u; default -> "NORMAL"; };
    }

    private static String levelLabel(String lvl) {
        return switch (lvl) { case "EASY" -> "초급"; case "HARD" -> "고급"; default -> "중급"; };
    }

    public synchronized RummikubStateResponse removeAi(String clientId) {
        if (phase != Phase.LOBBY) throw bad("대기방에서만 가능합니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 가능합니다");
        for (int i = players.size() - 1; i >= 0; i--) {
            if (players.get(i).ai) {
                players.remove(i);
                clientSeats.clear();
                for (int k = 0; k < players.size(); k++) clientSeats.put(players.get(k).clientId, k);
                break;
            }
        }
        return me(clientId);
    }

    // =================== AI 봇 ===================

    private void advanceAis() {
        int guard = 0;
        while (phase == Phase.PLAYING && players.get(currentSeat).ai && guard++ < 100) {
            Player ai = players.get(currentSeat);
            List<List<Integer>> plan = botPlan(ai);
            if (plan != null) {
                try { applyPlay(ai, plan); continue; }
                catch (RuntimeException ignore) { /* 계획이 무효면 뽑기로 */ }
            }
            applyDraw(ai);
        }
    }

    /**
     * 봇의 이번 턴 계획(놓을 수 없으면 null → 뽑기).
     * 난이도: EASY=새 세트만(테이블 확장·조커 X), NORMAL=새 세트+테이블 확장, HARD=거기에 조커까지 활용.
     */
    private List<List<Integer>> botPlan(Player ai) {
        boolean canExtend = !"EASY".equals(ai.aiLevel);
        boolean useJokers = "HARD".equals(ai.aiLevel);

        List<List<Integer>> newSets = useJokers
                ? findSetsWithJokers(new ArrayList<>(ai.rack))
                : findSets(new ArrayList<>(ai.rack));

        if (!ai.melded) {
            int sum = 0;
            for (var s : newSets) sum += Math.max(0, setValue(s));
            if (newSets.isEmpty() || sum < 30) return null;   // 첫 등록 30점 미달 → 뽑기
            List<List<Integer>> proposed = new ArrayList<>();
            for (var s : table) proposed.add(new ArrayList<>(s));
            proposed.addAll(newSets);
            return proposed;
        }
        // 이미 등록: 새 세트 + (중급 이상) 기존 세트에 붙일 수 있는 타일 붙이기
        List<List<Integer>> proposed = new ArrayList<>();
        for (var s : table) proposed.add(new ArrayList<>(s));
        Set<Integer> used = new HashSet<>();
        for (var s : newSets) used.addAll(s);
        List<Integer> leftover = new ArrayList<>();
        for (int id : ai.rack) if (!used.contains(id)) leftover.add(id);
        boolean extended = false;
        if (canExtend) {
            for (List<Integer> set : proposed) {
                boolean changed = true;
                while (changed) {
                    changed = false;
                    for (int i = 0; i < leftover.size(); i++) {
                        List<Integer> cand = new ArrayList<>(set);
                        cand.add(leftover.get(i));
                        if (setValue(cand) >= 0) { set.add(leftover.remove(i)); extended = true; changed = true; break; }
                    }
                }
            }
        }
        proposed.addAll(newSets);
        return (!newSets.isEmpty() || extended) ? proposed : null;
    }

    /** 랙에서 겹치지 않는 세트들을 그리디로 추출(조커 제외). */
    private List<List<Integer>> findSets(List<Integer> avail) {
        List<Integer> rem = new ArrayList<>(avail);
        List<List<Integer>> out = new ArrayList<>();
        while (true) {
            List<Integer> best = bestSet(rem);
            if (best == null) break;
            out.add(best);
            rem.removeAll(best);
        }
        return out;
    }

    /** 조커까지 활용해 세트를 추출(고급 봇). 우선 조커 없이 뽑고, 남은 조커로 실제타일 2개를 마저 묶는다. */
    private List<List<Integer>> findSetsWithJokers(List<Integer> avail) {
        List<Integer> rem = new ArrayList<>();
        List<Integer> jokers = new ArrayList<>();
        for (int id : avail) { if (id >= 104) jokers.add(id); else rem.add(id); }
        List<List<Integer>> out = new ArrayList<>();
        while (true) {
            List<Integer> best = bestSet(rem);
            if (best == null) break;
            out.add(best);
            rem.removeAll(best);
        }
        for (int jid : jokers) {
            List<Integer> made = bestPairWithJoker(rem, jid);
            if (made != null) { out.add(made); rem.removeAll(made); }
        }
        return out;
    }

    /** rem의 실제타일 2개 + 조커 1개로 만들 수 있는 최고 점수 세트(없으면 null). */
    private List<Integer> bestPairWithJoker(List<Integer> rem, int jokerId) {
        List<Integer> tiles = new ArrayList<>();
        for (int id : rem) if (id < 104) tiles.add(id);
        List<Integer> best = null;
        int bestVal = -1;
        for (int i = 0; i < tiles.size(); i++) {
            for (int j = i + 1; j < tiles.size(); j++) {
                List<Integer> cand = new ArrayList<>(List.of(tiles.get(i), tiles.get(j), jokerId));
                int v = setValue(cand);
                if (v > bestVal) { bestVal = v; best = cand; }
            }
        }
        return best;
    }

    /** rem에서 가장 점수 높은 유효 세트(조커 제외). 없으면 null. */
    private List<Integer> bestSet(List<Integer> rem) {
        List<Integer> tiles = new ArrayList<>();
        for (int id : rem) if (id < 104) tiles.add(id);
        List<Integer> best = null;
        int bestVal = -1;

        // 그룹: 숫자 → (색 → id)
        Map<Integer, Map<String, Integer>> byNum = new HashMap<>();
        for (int id : tiles) {
            Tile t = TILES[id];
            byNum.computeIfAbsent(t.number(), k -> new HashMap<>()).putIfAbsent(t.color(), id);
        }
        for (var e : byNum.entrySet()) {
            if (e.getValue().size() >= 3) {
                List<Integer> g = new ArrayList<>(e.getValue().values());
                int v = setValue(g);
                if (v > bestVal) { bestVal = v; best = g; }
            }
        }
        // 런: 색 → (숫자 → id)
        Map<String, Map<Integer, Integer>> byColor = new HashMap<>();
        for (int id : tiles) {
            Tile t = TILES[id];
            byColor.computeIfAbsent(t.color(), k -> new HashMap<>()).putIfAbsent(t.number(), id);
        }
        for (var e : byColor.entrySet()) {
            List<Integer> nums = new ArrayList<>(e.getValue().keySet());
            Collections.sort(nums);
            int i = 0;
            while (i < nums.size()) {
                int j = i;
                while (j + 1 < nums.size() && nums.get(j + 1) == nums.get(j) + 1) j++;
                if (j - i + 1 >= 3) {
                    List<Integer> run = new ArrayList<>();
                    for (int k = i; k <= j; k++) run.add(e.getValue().get(nums.get(k)));
                    int v = setValue(run);
                    if (v > bestVal) { bestVal = v; best = run; }
                }
                i = j + 1;
            }
        }
        return best;
    }

    /** 세트를 표시용 순서로 정규화: 런은 숫자 오름차순(조커는 빈칸에 삽입), 그룹은 색 순서. */
    private List<Integer> canonicalize(List<Integer> set) {
        List<Integer> reals = new ArrayList<>();
        List<Integer> jokers = new ArrayList<>();
        for (int id : set) { if (id >= 104) jokers.add(id); else reals.add(id); }
        if (reals.isEmpty()) return new ArrayList<>(set);

        // 그룹 판정: 실제 타일 숫자가 모두 같음
        int num = TILES[reals.get(0)].number();
        boolean group = set.size() <= 4 && reals.stream().allMatch(id -> TILES[id].number() == num);
        if (group) {
            reals.sort(Comparator.comparingInt(id -> colorRank(TILES[id].color())));
            List<Integer> out = new ArrayList<>(reals);
            out.addAll(jokers); // 조커는 뒤에
            return out;
        }
        // 런: 숫자 오름차순 + 사이 빈칸을 조커로 채우고 남는 조커는 뒤에
        reals.sort(Comparator.comparingInt(id -> TILES[id].number()));
        List<Integer> out = new ArrayList<>();
        int ji = 0, prev = -1;
        for (int id : reals) {
            int n = TILES[id].number();
            if (prev >= 0) for (int g = prev + 1; g < n && ji < jokers.size(); g++) out.add(jokers.get(ji++));
            out.add(id);
            prev = n;
        }
        while (ji < jokers.size()) out.add(jokers.get(ji++));
        return out;
    }

    private static final String[] COLOR_DISPLAY = {"RED", "ORANGE", "BLUE", "BLACK"};
    private static int colorRank(String c) {
        for (int i = 0; i < COLOR_DISPLAY.length; i++) if (COLOR_DISPLAY[i].equals(c)) return i;
        return 99;
    }

    // =================== 세트 검증 ===================

    /** 세트 점수(유효하면 ≥0, 아니면 -1). 그룹/런 판정 + 조커 처리. */
    int setValue(List<Integer> ids) {
        if (ids == null || ids.size() < 3 || ids.size() > 13) return -1;
        List<Tile> tiles = new ArrayList<>();
        for (int id : ids) {
            if (id < 0 || id >= 106) return -1;
            tiles.add(TILES[id]);
        }
        int jokers = (int) tiles.stream().filter(Tile::joker).count();
        List<Tile> real = tiles.stream().filter(t -> !t.joker()).toList();
        if (real.isEmpty()) return -1;

        int group = groupValue(real, jokers, tiles.size());
        if (group >= 0) return group;
        return runValue(real, jokers, tiles.size());
    }

    /** 그룹: 같은 숫자, 서로 다른 색. */
    private int groupValue(List<Tile> real, int jokers, int size) {
        if (size > 4) return -1;
        int num = real.get(0).number();
        Set<String> colors = new HashSet<>();
        for (Tile t : real) {
            if (t.number() != num) return -1;
            if (!colors.add(t.color())) return -1; // 색 중복
        }
        return num * size; // 조커도 num으로 계산
    }

    /** 런: 같은 색, 연속 숫자(조커가 빈칸 채움). */
    private int runValue(List<Tile> real, int jokers, int size) {
        String color = real.get(0).color();
        Set<Integer> nums = new HashSet<>();
        for (Tile t : real) {
            if (!t.color().equals(color)) return -1;
            if (!nums.add(t.number())) return -1; // 숫자 중복
        }
        int min = nums.stream().min(Integer::compare).get();
        int max = nums.stream().max(Integer::compare).get();
        if (max - min + 1 > size) return -1;
        // [s, s+size-1] 창이 1..13 안에 있고 모든 실제 숫자를 포함하는 s 탐색
        for (int s = Math.max(1, max - size + 1); s <= Math.min(min, 13 - size + 1); s++) {
            final int start = s;
            boolean ok = nums.stream().allMatch(n -> n >= start && n <= start + size - 1);
            if (ok) {
                int sum = 0;
                for (int k = s; k <= s + size - 1; k++) sum += k;
                return sum;
            }
        }
        return -1;
    }

    // =================== 응답 ===================

    private RummikubStateResponse buildResponse(String clientId) {
        long now = System.currentTimeMillis();
        if (phase == null) return RummikubStateResponse.notStarted(now);

        Integer mySeat = clientSeats.get(clientId);
        Player me = mySeat == null ? null : players.get(mySeat);
        boolean joined = me != null;

        List<PlayerView> pv = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            pv.add(new PlayerView(i + 1, p.nick, p.rack.size(), p.melded));
        }

        List<TileView> rack = new ArrayList<>();
        if (joined) for (int id : me.rack) rack.add(tileView(id));

        List<List<TileView>> tableView = new ArrayList<>();
        for (List<Integer> set : table) {
            List<TileView> ts = new ArrayList<>();
            for (int id : set) ts.add(tileView(id));
            tableView.add(ts);
        }

        return new RummikubStateResponse(
                phase.name(),
                now,
                clientId.equals(hostClientId),
                joined,
                joined ? mySeat + 1 : 0,
                joined ? me.nick : null,
                pv,
                rack,
                tableView,
                drawPile.size(),
                currentSeat + 1,
                joined && phase == Phase.PLAYING && mySeat == currentSeat,
                joined && me.melded,
                winnerSeat < 0 ? -1 : winnerSeat + 1,
                winnerSeat < 0 ? null : players.get(winnerSeat).nick,
                lastAction,
                players.size(),
                phase == Phase.PLAYING ? turnDeadlineMs : 0
        );
    }

    private TileView tileView(int id) {
        Tile t = TILES[id];
        return new TileView(t.id(), t.color(), t.number(), t.joker());
    }

    // =================== RoomGame ===================

    @Override public synchronized String roomStatus() {
        if (phase == null || phase == Phase.LOBBY) return "WAITING";
        return phase == Phase.ENDED ? "ENDED" : "PLAYING";
    }
    @Override public synchronized int playerCount() {
        // 봇 제외: 사람만 센다(사람이 다 나가면 빈 방으로 간주돼 즉시 삭제)
        return (int) players.stream().filter(p -> !p.ai && !leftClients.contains(p.clientId)).count();
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

    // =================== 유틸 ===================

    private void nextTurn() {
        currentSeat = (currentSeat + 1) % players.size();
        turnDeadlineMs = System.currentTimeMillis() + turnMs;
    }

    private void sortRack(Player p) {
        p.rack.sort((a, b) -> {
            Tile ta = TILES[a], tb = TILES[b];
            if (ta.joker() != tb.joker()) return ta.joker() ? 1 : -1;
            if (ta.joker()) return 0;
            int c = ta.color().compareTo(tb.color());
            return c != 0 ? c : Integer.compare(ta.number(), tb.number());
        });
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
        hostClientId = null;
        players.clear();
        clientSeats.clear();
        leftClients.clear();
        table.clear();
        drawPile.clear();
        currentSeat = 0;
        winnerSeat = -1;
        lastAction = null;
        turnDeadlineMs = 0;
        turnMs = DEFAULT_TURN_MS;
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
