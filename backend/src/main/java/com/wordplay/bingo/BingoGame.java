package com.wordplay.bingo;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.bingo.dto.BingoStateResponse;
import com.wordplay.bingo.dto.BingoStateResponse.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 빙고 방(인메모리). 판 크기 3×3/4×4/5×5(숫자 범위 1~15/1~25/1~50).
 * 각자 판을 직접 채우거나 랜덤으로 채우고, 봇 진행자가 앞에서 숫자를 하나씩 뽑는다.
 * 뽑힌 숫자는 모든 판에 자동 체크되고, 먼저 목표 줄 수(빙고)를 채운 사람이 승리.
 */
public class BingoGame implements RoomGame {

    enum Phase { LOBBY, PLAYING, ENDED }
    enum Mode { AUTO, TURN }   // AUTO: 봇이 자동으로 뽑음, TURN: 참가자가 번갈아 지목

    private static final long DRAW_INTERVAL_MS = 3_000;
    private static final long TURN_MS = 30_000;   // TURN 모드 한 차례 제한시간(초과 시 랜덤 지목)

    static final class Player {
        final String clientId; String nick;
        int[] board = null;              // 크기 size*size, 미설정이면 null
        boolean ready = false;
        Player(String clientId, String nick) { this.clientId = clientId; this.nick = nick; }
    }

    private Phase phase = null;
    private long lastActiveMs = System.currentTimeMillis();
    private String hostClientId = null;
    private final List<Player> players = new ArrayList<>();
    private final Map<String, Integer> seats = new HashMap<>();
    private final Set<String> leftClients = new HashSet<>();

    private int size = 5;
    private int range = 50;
    private int target = 3;
    private Mode mode = Mode.AUTO;

    private final List<Integer> drawn = new ArrayList<>();
    private final Set<Integer> drawnSet = new HashSet<>();
    private long nextDrawAt = 0;
    private int currentTurnSeat = -1;   // TURN 모드: 지금 지목할 좌석(0-based), 아니면 -1
    private long turnEndsAt = 0;        // TURN 모드: 이번 차례 마감 시각
    private int winnerSeat = -1;
    private long version = 0;

    // =================== 명령 ===================

    public synchronized BingoStateResponse newGame(String clientId, String nick, Integer size, Integer target) {
        return newGame(clientId, nick, size, target, null);
    }

    public synchronized BingoStateResponse newGame(String clientId, String nick, Integer size, Integer target, String mode) {
        reset();
        phase = Phase.LOBBY;
        hostClientId = clientId;
        setSize(size);
        this.target = clampInt(target, 1, 5, 3);
        this.mode = "TURN".equalsIgnoreCase(mode) ? Mode.TURN : Mode.AUTO;
        addPlayer(clientId, nick);
        touch();
        return me(clientId);
    }

    private void setSize(Integer s) {
        int v = s == null ? 5 : s;
        this.size = (v == 3 || v == 4 || v == 5) ? v : 5;
        this.range = switch (this.size) { case 3 -> 15; case 4 -> 25; default -> 50; };
    }

    public synchronized BingoStateResponse join(String clientId, String nick) {
        if (phase == null) throw bad("생성된 방이 없습니다");
        if (phase != Phase.LOBBY) throw bad("이미 진행 중이라 참가할 수 없습니다");
        if (!seats.containsKey(clientId)) {
            if (players.size() >= 8) throw bad("정원(8명)이 찼습니다");
            addPlayer(clientId, nick);
        } else {
            players.get(seats.get(clientId)).nick = trimNick(nick);
        }
        touch();
        return me(clientId);
    }

    /** 내 판 설정(직접 고르거나 랜덤). 숫자는 size*size개, 1~range, 중복 없이. */
    public synchronized BingoStateResponse setBoard(String clientId, List<Integer> numbers) {
        Integer seat = seats.get(clientId);
        if (seat == null) throw bad("참가하지 않은 기기입니다");
        if (phase != Phase.LOBBY) throw bad("대기방에서만 판을 바꿀 수 있습니다");
        int need = size * size;
        if (numbers == null || numbers.size() != need) throw bad(need + "개의 숫자가 필요합니다");
        Set<Integer> uniq = new HashSet<>();
        int[] b = new int[need];
        for (int i = 0; i < need; i++) {
            int n = numbers.get(i);
            if (n < 1 || n > range) throw bad("숫자는 1~" + range + " 범위여야 합니다");
            if (!uniq.add(n)) throw bad("중복된 숫자가 있습니다");
            b[i] = n;
        }
        Player p = players.get(seat);
        p.board = b;
        p.ready = true;
        touch();
        return me(clientId);
    }

    public synchronized BingoStateResponse start(String clientId) {
        if (phase != Phase.LOBBY) throw bad("지금 시작할 수 없습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 시작할 수 있습니다");
        if (players.size() < 2) throw bad("최소 2명이 필요합니다");
        for (Player p : players) if (p.board == null) throw bad("모든 참가자가 판을 완성해야 합니다");
        drawn.clear();
        drawnSet.clear();
        winnerSeat = -1;
        phase = Phase.PLAYING;
        long now = System.currentTimeMillis();
        if (mode == Mode.TURN) {
            currentTurnSeat = ThreadLocalRandom.current().nextInt(players.size());
            turnEndsAt = now + TURN_MS;
            nextDrawAt = 0;
        } else {
            nextDrawAt = now + DRAW_INTERVAL_MS;
            currentTurnSeat = -1;
            turnEndsAt = 0;
        }
        touch();
        return me(clientId);
    }

    /** TURN 모드: 자기 차례에 숫자 하나를 지목. 모든 판에 반영되고 다음 사람 차례로. */
    public synchronized BingoStateResponse callNumber(String clientId, int n) {
        tick();
        Integer seat = seats.get(clientId);
        if (seat == null) throw bad("참가하지 않은 기기입니다");
        if (phase != Phase.PLAYING) throw bad("진행 중이 아닙니다");
        if (mode != Mode.TURN) throw bad("지목 모드가 아닙니다");
        if (seat != currentTurnSeat) throw bad("당신의 차례가 아닙니다");
        if (n < 1 || n > range) throw bad("숫자는 1~" + range + " 범위여야 합니다");
        if (drawnSet.contains(n)) throw bad("이미 나온 숫자입니다");
        applyDraw(n);
        if (phase == Phase.PLAYING) advanceTurn();
        return me(clientId);
    }

    private void advanceTurn() {
        currentTurnSeat = (currentTurnSeat + 1) % players.size();
        turnEndsAt = System.currentTimeMillis() + TURN_MS;
    }

    public synchronized BingoStateResponse me(String clientId) {
        lastActiveMs = System.currentTimeMillis();
        tick();
        return build(clientId);
    }

    public synchronized BingoStateResponse resetGame() {
        reset();
        return BingoStateResponse.notStarted(System.currentTimeMillis());
    }

    // =================== 진행 ===================

    /** 진행 처리(폴링에 얹어). AUTO는 일정 간격 자동 뽑기, TURN은 차례 시간 초과 시 랜덤 지목. */
    private void tick() {
        if (phase != Phase.PLAYING) return;
        long now = System.currentTimeMillis();
        int guard = 0;
        if (mode == Mode.AUTO) {
            while (phase == Phase.PLAYING && nextDrawAt > 0 && now >= nextDrawAt && guard++ < 60) {
                drawOne();
                if (phase == Phase.PLAYING) nextDrawAt = now < nextDrawAt + DRAW_INTERVAL_MS ? nextDrawAt + DRAW_INTERVAL_MS : now + DRAW_INTERVAL_MS;
            }
        } else {
            while (phase == Phase.PLAYING && turnEndsAt > 0 && now >= turnEndsAt && guard++ < 60) {
                drawOne();                              // 시간 초과: 현재 차례 대신 랜덤 지목
                if (phase == Phase.PLAYING) advanceTurn();
                now = System.currentTimeMillis();
            }
        }
    }

    private void drawOne() {
        if (drawnSet.size() >= range) { finishByMostLines(); return; }
        int n;
        do { n = ThreadLocalRandom.current().nextInt(range) + 1; } while (drawnSet.contains(n));
        applyDraw(n);
    }

    private void applyDraw(int n) {
        drawn.add(n);
        drawnSet.add(n);
        version++;
        // 승리 판정: 목표 줄 수 도달자(가장 줄 많은 사람 우선, 동률은 낮은 좌석)
        int bestSeat = -1, bestLines = -1;
        for (int i = 0; i < players.size(); i++) {
            int l = linesOf(players.get(i));
            if (l >= target && l > bestLines) { bestLines = l; bestSeat = i; }
        }
        if (bestSeat >= 0) { winnerSeat = bestSeat; phase = Phase.ENDED; nextDrawAt = 0; }
        else if (drawnSet.size() >= range) finishByMostLines();
    }

    private void finishByMostLines() {
        int bestSeat = -1, bestLines = -1;
        for (int i = 0; i < players.size(); i++) {
            int l = linesOf(players.get(i));
            if (l > bestLines) { bestLines = l; bestSeat = i; }
        }
        winnerSeat = bestSeat;
        phase = Phase.ENDED;
        nextDrawAt = 0;
    }

    /** 완성된 줄 수(가로/세로/대각선). 셀은 drawnSet에 있으면 체크된 것. */
    private int linesOf(Player p) {
        if (p.board == null) return 0;
        int lines = 0;
        // 가로
        for (int r = 0; r < size; r++) {
            boolean all = true;
            for (int c = 0; c < size; c++) if (!drawnSet.contains(p.board[r * size + c])) { all = false; break; }
            if (all) lines++;
        }
        // 세로
        for (int c = 0; c < size; c++) {
            boolean all = true;
            for (int r = 0; r < size; r++) if (!drawnSet.contains(p.board[r * size + c])) { all = false; break; }
            if (all) lines++;
        }
        // 대각선 ↘
        boolean d1 = true;
        for (int i = 0; i < size; i++) if (!drawnSet.contains(p.board[i * size + i])) { d1 = false; break; }
        if (d1) lines++;
        // 대각선 ↙
        boolean d2 = true;
        for (int i = 0; i < size; i++) if (!drawnSet.contains(p.board[i * size + (size - 1 - i)])) { d2 = false; break; }
        if (d2) lines++;
        return lines;
    }

    // =================== 응답 ===================

    private BingoStateResponse build(String clientId) {
        long now = System.currentTimeMillis();
        if (phase == null) return BingoStateResponse.notStarted(now);

        Integer mySeat = seats.get(clientId);
        boolean joined = mySeat != null;

        List<PlayerView> pv = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            pv.add(new PlayerView(i + 1, p.nick, p.board != null, phase == Phase.LOBBY ? 0 : linesOf(p)));
        }

        List<Integer> myBoard = List.of();
        int myLines = 0;
        if (joined && players.get(mySeat).board != null) {
            int[] b = players.get(mySeat).board;
            List<Integer> bl = new ArrayList<>(b.length);
            for (int v : b) bl.add(v);
            myBoard = bl;
            myLines = phase == Phase.LOBBY ? 0 : linesOf(players.get(mySeat));
        }

        boolean myTurn = joined && mode == Mode.TURN && phase == Phase.PLAYING && mySeat == currentTurnSeat;
        return new BingoStateResponse(
                phase.name(), now,
                clientId.equals(hostClientId), joined,
                joined ? mySeat + 1 : 0, joined ? players.get(mySeat).nick : null,
                size, range, target, mode.name(),
                pv, myBoard, new ArrayList<>(drawn),
                drawn.isEmpty() ? -1 : drawn.get(drawn.size() - 1),
                nextDrawAt,
                (mode == Mode.TURN && phase == Phase.PLAYING) ? currentTurnSeat + 1 : -1,
                myTurn, turnEndsAt, myLines,
                winnerSeat < 0 ? -1 : winnerSeat + 1,
                winnerSeat < 0 ? null : players.get(winnerSeat).nick,
                players.size(), version
        );
    }

    // =================== RoomGame ===================

    @Override public synchronized String roomStatus() {
        if (phase == null || phase == Phase.LOBBY) return "WAITING";
        return phase == Phase.ENDED ? "ENDED" : "PLAYING";
    }
    @Override public synchronized int playerCount() {
        return (int) players.stream().filter(p -> !leftClients.contains(p.clientId)).count();
    }
    @Override public synchronized void leave(String clientId) {
        Integer seat = seats.get(clientId);
        if (seat == null) return;
        lastActiveMs = System.currentTimeMillis();
        if (phase == null || phase == Phase.LOBBY) {
            players.remove((int) seat);
            seats.clear();
            for (int i = 0; i < players.size(); i++) seats.put(players.get(i).clientId, i);
            if (clientId.equals(hostClientId)) hostClientId = players.isEmpty() ? null : players.get(0).clientId;
        } else {
            leftClients.add(clientId);
        }
    }
    @Override public synchronized String hostLabel() { return players.isEmpty() ? "" : players.get(0).nick; }
    @Override public synchronized boolean isEnded() { return phase == Phase.ENDED; }
    @Override public synchronized long lastActiveMs() { return lastActiveMs; }

    // 테스트용
    int sizeForTest() { return size; }
    int linesForTest(int seat) { return linesOf(players.get(seat)); }
    void debugDraw(int n) { if (phase == Phase.PLAYING && !drawnSet.contains(n)) applyDraw(n); }

    // =================== 유틸 ===================

    private void touch() { version++; lastActiveMs = System.currentTimeMillis(); }

    private void addPlayer(String clientId, String nick) {
        seats.put(clientId, players.size());
        players.add(new Player(clientId, trimNick(nick)));
    }

    private void reset() {
        phase = null; hostClientId = null;
        players.clear(); seats.clear(); leftClients.clear();
        size = 5; range = 50; target = 3; mode = Mode.AUTO;
        drawn.clear(); drawnSet.clear(); nextDrawAt = 0;
        currentTurnSeat = -1; turnEndsAt = 0; winnerSeat = -1; version = 0;
    }

    private static BusinessException bad(String msg) { return new BusinessException(ErrorCode.INVALID_INPUT, msg); }

    private static String trimNick(String nick) {
        String t = nick == null ? "" : nick.trim();
        if (t.isEmpty()) t = "익명";
        return t.length() > 16 ? t.substring(0, 16) : t;
    }

    private static int clampInt(Integer v, int min, int max, int def) {
        if (v == null) return def;
        return Math.max(min, Math.min(max, v));
    }
}
