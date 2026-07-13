package com.wordplay.othello;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.othello.dto.OthelloStateResponse;
import com.wordplay.othello.dto.OthelloStateResponse.PlayerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 오델로(리버시). 8×8, 2인(흑=1 선, 백=2). 인메모리·폴링.
 * 봇(초급/중급/고급) 또는 다른 유저와 대전.
 */
public class OthelloGame implements RoomGame {

    enum Phase { LOBBY, PLAYING, ENDED }
    private static final int[] DR = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final int[] DC = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final long DEFAULT_TURN_MS = 60_000L;

    // 고급 봇 위치 가중치
    private static final int[] WEIGHT = {
            100, -20, 10, 5, 5, 10, -20, 100,
            -20, -50, -2, -2, -2, -2, -50, -20,
            10, -2, -1, -1, -1, -1, -2, 10,
            5, -2, -1, -1, -1, -1, -2, 5,
            5, -2, -1, -1, -1, -1, -2, 5,
            10, -2, -1, -1, -1, -1, -2, 10,
            -20, -50, -2, -2, -2, -2, -50, -20,
            100, -20, 10, 5, 5, 10, -20, 100};

    static final class Player {
        final String clientId; String nick;
        boolean ai = false; String aiLevel = "NORMAL";
        int color = 0; // 1 흑, 2 백
        Player(String clientId, String nick) { this.clientId = clientId; this.nick = nick; }
    }

    private Phase phase = null;
    private long lastActiveMs = System.currentTimeMillis();
    private String hostClientId = null;
    private final List<Player> players = new ArrayList<>();
    private final Map<String, Integer> seats = new HashMap<>();
    private final Set<String> leftClients = new HashSet<>();

    private int hostColor = 1;         // 방장 색
    private long turnMs = DEFAULT_TURN_MS;
    private final int[] board = new int[64];
    private int currentColor = 1;
    private int lastMove = -1;
    private String lastAction = null;
    private long turnDeadlineMs = 0;
    private long botActAt = 0;
    private int winner = 0;            // 0 미정, 1 흑, 2 백, 3 무승부
    private long version = 0;

    // =================== 명령 ===================

    public synchronized OthelloStateResponse newGame(String clientId, String nick, String hostColorStr, Integer turnSec) {
        reset();
        phase = Phase.LOBBY;
        hostClientId = clientId;
        this.hostColor = "WHITE".equalsIgnoreCase(hostColorStr) ? 2 : 1;
        this.turnMs = (turnSec == null ? 60 : Math.max(15, Math.min(300, turnSec))) * 1000L;
        addPlayer(clientId, nick);
        players.get(0).color = hostColor;
        touch();
        return me(clientId);
    }

    public synchronized OthelloStateResponse join(String clientId, String nick) {
        if (phase == null) throw bad("생성된 방이 없습니다");
        if (phase != Phase.LOBBY) throw bad("이미 진행 중이라 참가할 수 없습니다");
        if (!seats.containsKey(clientId)) {
            if (players.size() >= 2) throw bad("이미 2명이 찼습니다");
            addPlayer(clientId, nick);
            players.get(players.size() - 1).color = 3 - hostColor;
        } else {
            players.get(seats.get(clientId)).nick = trimNick(nick);
        }
        touch();
        return me(clientId);
    }

    public synchronized OthelloStateResponse addBot(String clientId, String level) {
        if (phase != Phase.LOBBY) throw bad("대기방에서만 봇을 추가할 수 있습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 추가할 수 있습니다");
        if (players.size() >= 2) throw bad("이미 상대가 있습니다");
        String lvl = normalizeLevel(level);
        Player b = new Player("bot::" + System.nanoTime(), "🤖 봇(" + levelLabel(lvl) + ")");
        b.ai = true; b.aiLevel = lvl; b.color = 3 - hostColor;
        players.add(b);
        seats.put(b.clientId, players.size() - 1);
        touch();
        return me(clientId);
    }

    public synchronized OthelloStateResponse start(String clientId) {
        if (phase != Phase.LOBBY) throw bad("지금 시작할 수 없습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 시작할 수 있습니다");
        if (players.size() < 2) throw bad("상대(봇 또는 유저)가 필요합니다");
        for (int i = 0; i < 64; i++) board[i] = 0;
        board[idx(3, 3)] = 2; board[idx(3, 4)] = 1;
        board[idx(4, 3)] = 1; board[idx(4, 4)] = 2;
        currentColor = 1; // 흑 선
        lastMove = -1; winner = 0;
        lastAction = "게임 시작 · 흑 선";
        phase = Phase.PLAYING;
        turnDeadlineMs = System.currentTimeMillis() + turnMs;
        botActAt = System.currentTimeMillis() + botDelay();
        touch();
        return me(clientId);
    }

    public synchronized OthelloStateResponse place(String clientId, int cell) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        if (me.color != currentColor) throw bad("당신의 차례가 아닙니다");
        if (cell < 0 || cell >= 64 || board[cell] != 0) throw bad("놓을 수 없는 칸입니다");
        List<Integer> flips = flipsFor(currentColor, cell);
        if (flips.isEmpty()) throw bad("여기엔 놓을 수 없어요(뒤집을 돌이 없음)");
        applyMove(currentColor, cell, flips);
        return me(clientId);
    }

    public synchronized OthelloStateResponse resetGame() {
        reset();
        return OthelloStateResponse.notStarted(System.currentTimeMillis());
    }

    public synchronized OthelloStateResponse me(String clientId) {
        lastActiveMs = System.currentTimeMillis();
        tick();
        return build(clientId);
    }

    // =================== 진행 ===================

    private void applyMove(int color, int cell, List<Integer> flips) {
        board[cell] = color;
        for (int f : flips) board[f] = color;
        lastMove = cell;
        lastAction = (color == 1 ? "흑" : "백") + "이 " + (char) ('A' + cell % 8) + (cell / 8 + 1) + " 착수";
        advanceAfterMove(color);
        touch();
    }

    /** 착수 후 차례 넘기기(양쪽 못 두면 종료, 한쪽만 못 두면 패스). */
    private void advanceAfterMove(int mover) {
        int next = 3 - mover;
        if (!hasMove(next)) {
            if (!hasMove(mover)) { endGame(); return; }
            // next 패스, mover가 계속
            currentColor = mover;
            lastAction += " · " + (next == 1 ? "흑" : "백") + " 둘 곳 없어 패스";
        } else {
            currentColor = next;
        }
        turnDeadlineMs = System.currentTimeMillis() + turnMs;
        botActAt = System.currentTimeMillis() + botDelay();
    }

    private void endGame() {
        int b = count(1), w = count(2);
        winner = b > w ? 1 : w > b ? 2 : 3;
        phase = Phase.ENDED;
        turnDeadlineMs = 0;
        lastAction = "게임 종료 · " + (winner == 3 ? "무승부" : (winner == 1 ? "흑" : "백") + " 승리") + " (흑 " + b + " : 백 " + w + ")";
    }

    private void tick() {
        if (phase != Phase.PLAYING) return;
        long now = System.currentTimeMillis();
        if (turnDeadlineMs > 0 && now >= turnDeadlineMs) {
            int cell = pickMove(currentColor, "NORMAL"); // 시간초과 자동 착수
            if (cell >= 0) applyMove(currentColor, cell, flipsFor(currentColor, cell));
            else endGame();
            return;
        }
        Player cur = seatColor(currentColor);
        if (cur != null && cur.ai && now >= botActAt) {
            int cell = pickMove(currentColor, cur.aiLevel);
            if (cell >= 0) applyMove(currentColor, cell, flipsFor(currentColor, cell));
            else endGame();
        }
    }

    // =================== 봇/수 선택 ===================

    private int pickMove(int color, String level) {
        List<Integer> moves = validMoves(color);
        if (moves.isEmpty()) return -1;
        if ("EASY".equals(level)) return moves.get(ThreadLocalRandom.current().nextInt(moves.size()));
        if ("MASTER".equals(level)) return pickMasterMove(color);
        if ("HARD".equals(level)) {
            long[] sc = new long[moves.size()];
            for (int k = 0; k < moves.size(); k++) {
                int[] sim = board.clone();
                sim[moves.get(k)] = color; for (int f : flipsFor(color, moves.get(k))) sim[f] = color;
                long s = 0;
                for (int i = 0; i < 64; i++) s += sim[i] == color ? WEIGHT[i] : sim[i] == (3 - color) ? -WEIGHT[i] : 0;
                sc[k] = s;
            }
            return pickAmongBest(moves, sc, 6);  // 최선급 여러 수 중 랜덤
        }
        // NORMAL: 가장 많이 뒤집되 코너 우선, X칸 회피
        long[] sc = new long[moves.size()];
        for (int k = 0; k < moves.size(); k++) {
            long s = flipsFor(color, moves.get(k)).size();
            if (isCorner(moves.get(k))) s += 30;
            if (isXSquare(moves.get(k))) s -= 15;
            sc[k] = s;
        }
        return pickAmongBest(moves, sc, 2);
    }

    /** 최고점에서 margin 이내인 수들 중 하나를 랜덤 선택(결정론 → 변주). */
    private static int pickAmongBest(List<Integer> moves, long[] scores, long margin) {
        long best = Long.MIN_VALUE;
        for (long s : scores) if (s > best) best = s;
        List<Integer> cand = new ArrayList<>();
        for (int k = 0; k < moves.size(); k++) if (scores[k] >= best - margin) cand.add(moves.get(k));
        return cand.get(ThreadLocalRandom.current().nextInt(cand.size()));
    }

    // =================== 초고수(MASTER): 알파-베타 미니맥스 ===================

    /** 종반에는 끝까지, 중반에는 깊게 읽어 최선급 수 중 하나를 고른다(변주 포함). */
    private int pickMasterMove(int color) {
        List<Integer> moves = validMoves(color);
        if (moves.isEmpty()) return -1;
        int empties = 0; for (int v : board) if (v == 0) empties++;
        boolean endgame = empties <= 12;
        // 종반(≤12칸)은 끝까지 완전탐색, 중반은 깊게(오프닝만 살짝 얕게)
        int depth = endgame ? empties : (empties >= 46 ? 6 : 7);
        // 변주는 오프닝에서만(경로 다양화). 게임이 본격화되면 정확한 최선수만 둔다 → 최강 플레이 회복
        long margin = empties >= 48 ? 6 : 0;
        moves.sort((a, b) -> Integer.compare(WEIGHT[b], WEIGHT[a])); // 이동 정렬로 가지치기 강화
        int opp = 3 - color;
        long best = NEG;
        long[] scores = new long[moves.size()];
        for (int k = 0; k < moves.size(); k++) {
            int m = moves.get(k);
            int[] nb = board.clone();
            nb[m] = color; for (int f : flipsFor(color, m)) nb[f] = color;
            // 루트에서 alpha를 margin만큼 완화 → 최선-margin 이상인 수는 정확히 평가(그 이하만 가지치기)
            long a = Math.max(best - margin, NEG);
            long v = -negamax(nb, opp, depth - 1, -POS, -a);
            scores[k] = v;
            if (v > best) best = v;
        }
        return pickAmongBest(moves, scores, margin);
    }

    private static final long NEG = Long.MIN_VALUE / 4, POS = Long.MAX_VALUE / 4;

    private long negamax(int[] bd, int color, int depth, long alpha, long beta) {
        int opp = 3 - color;
        List<Integer> moves = movesOn(bd, color);
        if (moves.isEmpty()) {
            if (movesOn(bd, opp).isEmpty()) return terminalScore(bd, color); // 양쪽 다 못 둠 → 종료
            return -negamax(bd, opp, depth, -beta, -alpha);                  // 한쪽만 패스
        }
        if (depth <= 0) return heuristic(bd, color);
        moves.sort((a, b) -> Integer.compare(WEIGHT[b], WEIGHT[a]));
        long best = NEG;
        for (int m : moves) {
            int[] nb = bd.clone();
            nb[m] = color; for (int f : flipsOn(bd, color, m)) nb[f] = color;
            long v = -negamax(nb, opp, depth - 1, -beta, -alpha);
            if (v > best) best = v;
            if (best > alpha) alpha = best;
            if (alpha >= beta) break;   // 알파-베타 가지치기
        }
        return best;
    }

    /** 게임 종료 국면 평가: 돌 차이가 절대적(승패 우선). */
    private static long terminalScore(int[] bd, int color) {
        int my = 0, op = 0;
        for (int v : bd) { if (v == color) my++; else if (v != 0) op++; }
        int diff = my - op;
        long sign = diff > 0 ? 1 : diff < 0 ? -1 : 0;
        return sign * 10_000_000L + diff * 1000L;
    }

    /** 중간 국면 휴리스틱: 위치가중치 + 착수가능수(기동력) + 프론티어 + 코너. */
    private static long heuristic(int[] bd, int color) {
        int opp = 3 - color;
        long pos = 0; int myF = 0, opF = 0;
        for (int i = 0; i < 64; i++) {
            int v = bd[i];
            if (v == 0) continue;
            if (v == color) { pos += WEIGHT[i]; if (isFrontier(bd, i)) myF++; }
            else { pos -= WEIGHT[i]; if (isFrontier(bd, i)) opF++; }
        }
        long mob = movesOn(bd, color).size() - (long) movesOn(bd, opp).size();
        long frontier = opF - (long) myF;   // 프론티어(빈칸에 접한 돌)는 적을수록 유리
        long corner = cornerCount(bd, color) - (long) cornerCount(bd, opp);
        return pos + mob * 15 + frontier * 10 + corner * 80;
    }

    private static boolean isFrontier(int[] bd, int i) {
        int r = i / 8, c = i % 8;
        for (int d = 0; d < 8; d++) { int nr = r + DR[d], nc = c + DC[d]; if (inBoard(nr, nc) && bd[idx(nr, nc)] == 0) return true; }
        return false;
    }
    private static int cornerCount(int[] bd, int color) {
        int n = 0; for (int c : new int[]{0, 7, 56, 63}) if (bd[c] == color) n++; return n;
    }
    private static List<Integer> movesOn(int[] bd, int color) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < 64; i++) if (bd[i] == 0 && canFlip(bd, color, i)) out.add(i);
        return out;
    }
    private static boolean canFlip(int[] bd, int color, int cell) {
        int r = cell / 8, c = cell % 8, opp = 3 - color;
        for (int d = 0; d < 8; d++) {
            int nr = r + DR[d], nc = c + DC[d], cnt = 0;
            while (inBoard(nr, nc) && bd[idx(nr, nc)] == opp) { cnt++; nr += DR[d]; nc += DC[d]; }
            if (cnt > 0 && inBoard(nr, nc) && bd[idx(nr, nc)] == color) return true;
        }
        return false;
    }
    private static List<Integer> flipsOn(int[] bd, int color, int cell) {
        List<Integer> flips = new ArrayList<>();
        int r = cell / 8, c = cell % 8, opp = 3 - color;
        for (int d = 0; d < 8; d++) {
            List<Integer> line = new ArrayList<>();
            int nr = r + DR[d], nc = c + DC[d];
            while (inBoard(nr, nc) && bd[idx(nr, nc)] == opp) { line.add(idx(nr, nc)); nr += DR[d]; nc += DC[d]; }
            if (!line.isEmpty() && inBoard(nr, nc) && bd[idx(nr, nc)] == color) flips.addAll(line);
        }
        return flips;
    }

    // =================== 규칙 ===================

    private List<Integer> validMoves(int color) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < 64; i++) if (board[i] == 0 && !flipsFor(color, i).isEmpty()) out.add(i);
        return out;
    }
    private boolean hasMove(int color) {
        for (int i = 0; i < 64; i++) if (board[i] == 0 && !flipsFor(color, i).isEmpty()) return true;
        return false;
    }
    /** cell에 color가 두면 뒤집히는 돌들. 없으면 빈 리스트. */
    private List<Integer> flipsFor(int color, int cell) {
        List<Integer> flips = new ArrayList<>();
        if (board[cell] != 0) return flips;
        int r = cell / 8, c = cell % 8, opp = 3 - color;
        for (int d = 0; d < 8; d++) {
            List<Integer> line = new ArrayList<>();
            int nr = r + DR[d], nc = c + DC[d];
            while (inBoard(nr, nc) && board[idx(nr, nc)] == opp) { line.add(idx(nr, nc)); nr += DR[d]; nc += DC[d]; }
            if (!line.isEmpty() && inBoard(nr, nc) && board[idx(nr, nc)] == color) flips.addAll(line);
        }
        return flips;
    }

    private static boolean isCorner(int i) { int r = i / 8, c = i % 8; return (r == 0 || r == 7) && (c == 0 || c == 7); }
    private static boolean isXSquare(int i) { int r = i / 8, c = i % 8; return (r == 1 || r == 6) && (c == 1 || c == 6); }
    private static boolean inBoard(int r, int c) { return r >= 0 && r < 8 && c >= 0 && c < 8; }
    private static int idx(int r, int c) { return r * 8 + c; }
    private int count(int color) { int n = 0; for (int v : board) if (v == color) n++; return n; }
    private Player seatColor(int color) { for (Player p : players) if (p.color == color) return p; return null; }

    // =================== 응답 ===================

    private OthelloStateResponse build(String clientId) {
        long now = System.currentTimeMillis();
        if (phase == null) return OthelloStateResponse.notStarted(now);
        Integer mySeat = seats.get(clientId);
        boolean joined = mySeat != null;
        int myColor = joined ? players.get(mySeat).color : 0;
        boolean myTurn = joined && phase == Phase.PLAYING && myColor == currentColor;

        List<PlayerView> pv = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            pv.add(new PlayerView(i + 1, p.nick, p.ai, p.color));
        }
        List<Integer> boardList = new ArrayList<>(64);
        for (int v : board) boardList.add(v);
        List<Integer> valid = myTurn ? validMoves(myColor) : List.of();

        return new OthelloStateResponse(
                phase.name(), now, clientId.equals(hostClientId), joined,
                joined ? mySeat + 1 : 0, joined ? players.get(mySeat).nick : null,
                myColor, hostColor, pv, boardList,
                phase == Phase.PLAYING ? currentColor : 0, myTurn, valid,
                count(1), count(2), lastMove, turnDeadlineMs, lastAction, winner,
                players.size(), version);
    }

    // =================== RoomGame ===================

    @Override public synchronized String roomStatus() {
        if (phase == null || phase == Phase.LOBBY) return "WAITING";
        return phase == Phase.ENDED ? "ENDED" : "PLAYING";
    }
    @Override public synchronized int playerCount() {
        return (int) players.stream().filter(p -> !p.ai && !leftClients.contains(p.clientId)).count();
    }
    @Override public synchronized String hostLabel() { return players.isEmpty() ? "" : players.get(0).nick; }
    @Override public synchronized boolean isEnded() { return phase == Phase.ENDED; }
    @Override public synchronized long lastActiveMs() { return lastActiveMs; }
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

    // 테스트용
    int[] boardForTest() { return board; }
    int countForTest(int color) { return count(color); }
    List<Integer> validForTest(int color) { return validMoves(color); }
    synchronized void forceBotNowForTest() { botActAt = 0; } // 봇 착수 지연 무시(테스트 진행용)
    synchronized int pickMoveForTest(int color, String level) { return pickMove(color, level); }

    // =================== 유틸 ===================

    private long botDelay() { return 700 + ThreadLocalRandom.current().nextInt(700); }
    private void touch() { version++; lastActiveMs = System.currentTimeMillis(); }
    private void addPlayer(String clientId, String nick) { seats.put(clientId, players.size()); players.add(new Player(clientId, trimNick(nick))); }
    private Player requirePlayer(String clientId) {
        Integer s = seats.get(clientId);
        if (s == null) throw bad("참가하지 않은 기기입니다");
        return players.get(s);
    }
    private void reset() {
        phase = null; hostClientId = null;
        players.clear(); seats.clear(); leftClients.clear();
        hostColor = 1; turnMs = DEFAULT_TURN_MS;
        for (int i = 0; i < 64; i++) board[i] = 0;
        currentColor = 1; lastMove = -1; lastAction = null;
        turnDeadlineMs = 0; botActAt = 0; winner = 0; version = 0;
    }
    private static String normalizeLevel(String s) {
        if (s == null) return "NORMAL";
        String u = s.toUpperCase();
        return (u.equals("EASY") || u.equals("HARD") || u.equals("MASTER")) ? u : "NORMAL";
    }
    private static String levelLabel(String lvl) {
        return switch (lvl) { case "EASY" -> "초급"; case "HARD" -> "고급"; case "MASTER" -> "초고수"; default -> "중급"; };
    }
    private static BusinessException bad(String msg) { return new BusinessException(ErrorCode.INVALID_INPUT, msg); }
    private static String trimNick(String nick) {
        String t = nick == null ? "" : nick.trim();
        if (t.isEmpty()) t = "익명";
        return t.length() > 16 ? t.substring(0, 16) : t;
    }
}
