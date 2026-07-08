package com.wordplay.rummikub;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.rummikub.dto.RummikubStateResponse;
import com.wordplay.rummikub.dto.RummikubStateResponse.PlayerView;
import com.wordplay.rummikub.dto.RummikubStateResponse.TileView;

import java.util.ArrayList;
import java.util.Collections;
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
        final List<Integer> rack = new ArrayList<>();
        boolean melded = false;
        Player(String clientId, String nick) { this.clientId = clientId; this.nick = nick; }
    }

    private Phase phase = null;
    private long lastActiveMs = System.currentTimeMillis();
    private String hostClientId = null;
    private final List<Player> players = new ArrayList<>();
    private final Map<String, Integer> clientSeats = new HashMap<>();

    private final List<List<Integer>> table = new ArrayList<>();
    private final List<Integer> drawPile = new ArrayList<>();
    private int currentSeat = 0;
    private int winnerSeat = -1;
    private String lastAction = null;

    // =================== 명령 ===================

    public synchronized RummikubStateResponse newGame(String clientId, String nick) {
        reset();
        phase = Phase.LOBBY;
        hostClientId = clientId;
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
        return me(clientId);
    }

    /** 턴 제출: 제안 테이블 전체. */
    public synchronized RummikubStateResponse play(String clientId, List<List<Integer>> proposed) {
        Player me = requirePlayer(clientId);
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        if (seatOf(me) != currentSeat) throw bad("당신의 차례가 아닙니다");
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

        // 커밋
        me.rack.removeAll(added);
        table.clear();
        for (List<Integer> s : proposed) table.add(new ArrayList<>(s));
        me.melded = true;
        if (me.rack.isEmpty()) {
            winnerSeat = seatOf(me);
            phase = Phase.ENDED;
            lastAction = me.nick + "님이 승리했습니다!";
        } else {
            nextTurn();
            lastAction = me.nick + "님이 타일을 내려놨습니다.";
        }
        return me(clientId);
    }

    /** 더미에서 1개 가져오고 턴 종료. */
    public synchronized RummikubStateResponse draw(String clientId) {
        Player me = requirePlayer(clientId);
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        if (seatOf(me) != currentSeat) throw bad("당신의 차례가 아닙니다");
        if (!drawPile.isEmpty()) {
            me.rack.add(drawPile.remove(drawPile.size() - 1));
            sortRack(me);
            lastAction = me.nick + "님이 타일을 가져왔습니다.";
        } else {
            lastAction = "더미가 비어 " + me.nick + "님이 턴을 넘겼습니다.";
        }
        nextTurn();
        return me(clientId);
    }

    public synchronized RummikubStateResponse resetGame() {
        reset();
        return RummikubStateResponse.notStarted(System.currentTimeMillis());
    }

    public synchronized RummikubStateResponse me(String clientId) {
        lastActiveMs = System.currentTimeMillis();
        return buildResponse(clientId);
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
                players.size()
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
    @Override public synchronized int playerCount() { return players.size(); }
    @Override public synchronized String hostLabel() { return players.isEmpty() ? "" : players.get(0).nick; }
    @Override public synchronized boolean isEnded() { return phase == Phase.ENDED; }
    @Override public synchronized long lastActiveMs() { return lastActiveMs; }

    // =================== 유틸 ===================

    private void nextTurn() { currentSeat = (currentSeat + 1) % players.size(); }

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
        table.clear();
        drawPile.clear();
        currentSeat = 0;
        winnerSeat = -1;
        lastAction = null;
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
