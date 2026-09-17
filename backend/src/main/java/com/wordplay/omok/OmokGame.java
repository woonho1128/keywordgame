package com.wordplay.omok;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.omok.dto.OmokStateResponse;
import com.wordplay.omok.dto.OmokStateResponse.PlayerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 오목(Gomoku) — 15×15, 자유룰/금수룰(렌주), 봇(초·중·고급). 인메모리·폴링.
 * 설계: OMOK_DESIGN.md.
 */
public class OmokGame implements RoomGame {

    enum Phase { LOBBY, PLAYING, ENDED }
    static final int N = 15;
    private static final int[][] DIRS = { {0, 1}, {1, 0}, {1, 1}, {1, -1} };
    private static final long[] WEIGHT = { 0, 1, 12, 120, 1200, 100_000 };

    static final class Player {
        final String clientId; String nick; boolean ai; String aiLevel = "NORMAL"; int color;
        Player(String clientId, String nick) { this.clientId = clientId; this.nick = nick; }
    }

    private Phase phase = null;
    private long lastActiveMs = System.currentTimeMillis();
    private String hostClientId = null;
    private final List<Player> players = new ArrayList<>();
    private final Map<String, Integer> seats = new HashMap<>();
    private final Set<String> leftClients = new HashSet<>();

    private String rule = "FREE";       // FREE / RENJU
    private int hostColor = 1;           // 1 흑(선), 2 백
    private final int[] board = new int[N * N];
    private int currentColor = 1;
    private int lastMove = -1;
    private String lastAction = null;
    private int winner = 0;              // 0 미정, 1 흑, 2 백, 3 무
    private int[] winLine = new int[0];
    private long botActAt = 0;
    private long version = 0;

    // =================== 명령 ===================

    public synchronized OmokStateResponse newGame(String clientId, String nick, String hostColorStr, String ruleStr) {
        reset();
        phase = Phase.LOBBY;
        hostClientId = clientId;
        this.hostColor = "WHITE".equalsIgnoreCase(hostColorStr) ? 2 : 1;
        this.rule = "RENJU".equalsIgnoreCase(ruleStr) ? "RENJU" : "FREE";
        addPlayer(clientId, nick);
        players.get(0).color = hostColor;
        touch();
        return me(clientId);
    }

    public synchronized OmokStateResponse join(String clientId, String nick) {
        if (phase == null) throw bad("생성된 방이 없습니다");
        if (phase != Phase.LOBBY) throw bad("이미 진행 중이라 참가할 수 없습니다");
        if (!seats.containsKey(clientId)) {
            if (players.size() >= 2) throw bad("이미 2명이 찼습니다");
            addPlayer(clientId, nick);
            players.get(players.size() - 1).color = 3 - hostColor;
        } else players.get(seats.get(clientId)).nick = trimNick(nick);
        touch();
        return me(clientId);
    }

    public synchronized OmokStateResponse addBot(String clientId, String level) {
        if (phase != Phase.LOBBY) throw bad("대기방에서만 봇을 추가할 수 있습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 추가할 수 있습니다");
        if (players.size() >= 2) throw bad("이미 상대가 있습니다");
        String lvl = normalizeLevel(level);
        Player b = new Player("bot::" + System.nanoTime(), "🤖 봇(" + levelLabel(lvl) + ")");
        b.ai = true; b.aiLevel = lvl; b.color = 3 - hostColor;
        players.add(b); seats.put(b.clientId, players.size() - 1);
        touch();
        return me(clientId);
    }

    public synchronized OmokStateResponse start(String clientId) {
        if (phase != Phase.LOBBY) throw bad("지금 시작할 수 없습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 시작할 수 있습니다");
        if (players.size() < 2) throw bad("상대(봇 또는 유저)가 필요합니다");
        for (int i = 0; i < board.length; i++) board[i] = 0;
        currentColor = 1; lastMove = -1; winner = 0; winLine = new int[0];
        lastAction = "게임 시작 · 흑 선";
        phase = Phase.PLAYING;
        botActAt = System.currentTimeMillis() + botDelay();
        touch();
        return me(clientId);
    }

    public synchronized OmokStateResponse place(String clientId, int cell) {
        tick();
        Player me = requirePlayer(clientId);
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        if (me.color != currentColor) throw bad("당신의 차례가 아닙니다");
        if (cell < 0 || cell >= board.length || board[cell] != 0) throw bad("놓을 수 없는 칸입니다");
        if (isForbidden(cell, currentColor)) throw bad("금수 자리입니다(3-3/4-4/장목)");
        applyMove(currentColor, cell);
        return me(clientId);
    }

    public synchronized OmokStateResponse me(String clientId) {
        lastActiveMs = System.currentTimeMillis();
        tick();
        return build(clientId);
    }

    // =================== 진행 ===================

    private void applyMove(int color, int cell) {
        board[cell] = color; lastMove = cell;
        lastAction = (color == 1 ? "흑" : "백") + " " + coord(cell);
        int[] w = winningLine(cell, color);
        if (w != null) { winner = color; winLine = w; phase = Phase.ENDED;
            lastAction += " · " + (color == 1 ? "흑" : "백") + " 5목 승리!"; touch(); return; }
        if (isBoardFull()) { winner = 3; phase = Phase.ENDED; lastAction = "무승부"; touch(); return; }
        currentColor = 3 - color;
        botActAt = System.currentTimeMillis() + botDelay();
        touch();
    }

    private void tick() {
        if (phase != Phase.PLAYING) return;
        Player cur = seatColor(currentColor);
        if (cur != null && cur.ai && System.currentTimeMillis() >= botActAt) {
            int cell = botMove(currentColor, cur.aiLevel);
            if (cell >= 0) applyMove(currentColor, cell);
            else { winner = 3; phase = Phase.ENDED; lastAction = "무승부"; touch(); }
        }
    }

    // =================== 승리 / 금수 ===================

    /** cell에 color가 놓였을 때 5목이면 승리 5칸 반환, 아니면 null. */
    private int[] winningLine(int cell, int color) {
        int r = cell / N, c = cell % N;
        for (int[] d : DIRS) {
            int cnt = 1;
            List<Integer> cells = new ArrayList<>(); cells.add(cell);
            for (int s = 1; s < 6; s++) { int rr = r + d[0] * s, cc = c + d[1] * s; if (in(rr, cc) && board[idx(rr, cc)] == color) { cells.add(idx(rr, cc)); cnt++; } else break; }
            for (int s = 1; s < 6; s++) { int rr = r - d[0] * s, cc = c - d[1] * s; if (in(rr, cc) && board[idx(rr, cc)] == color) { cells.add(0, idx(rr, cc)); cnt++; } else break; }
            boolean win = "RENJU".equals(rule) ? (color == 1 ? cnt == 5 : cnt >= 5) : cnt >= 5;
            if (win) {
                // 정확히 5칸(연속) 뽑기
                int[] out = new int[5];
                for (int i = 0; i < 5; i++) out[i] = cells.get(i);
                return out;
            }
        }
        return null;
    }

    /** 금수룰에서 흑의 금수(3-3/4-4/장목) 여부. 승리(정확히5)면 금수 아님. */
    private boolean isForbidden(int cell, int color) {
        if (!"RENJU".equals(rule) || color != 1) return false;
        board[cell] = 1;
        try {
            int fives = 0, overline = 0, fours = 0, threes = 0;
            int r = cell / N, c = cell % N;
            for (int[] d : DIRS) {
                int run = runLen(cell, 1, d);
                if (run == 5) fives++;
                if (run >= 6) overline++;
                if (fourInDir(cell, d)) fours++;
                if (openThreeInDir(cell, d)) threes++;
            }
            if (fives > 0) return false;                 // 승리 우선
            return overline > 0 || fours >= 2 || threes >= 2;
        } finally { board[cell] = 0; }
    }

    private int runLen(int cell, int color, int[] d) {
        int r = cell / N, c = cell % N, cnt = 1;
        for (int s = 1; s < 7; s++) { int rr = r + d[0] * s, cc = c + d[1] * s; if (in(rr, cc) && board[idx(rr, cc)] == color) cnt++; else break; }
        for (int s = 1; s < 7; s++) { int rr = r - d[0] * s, cc = c - d[1] * s; if (in(rr, cc) && board[idx(rr, cc)] == color) cnt++; else break; }
        return cnt;
    }

    /** 이 방향에 빈칸 하나 채우면 5가 되는 '4'가 있는가(흑 기준). */
    private boolean fourInDir(int cell, int[] d) {
        int r = cell / N, c = cell % N;
        for (int off = -4; off <= 4; off++) {
            if (off == 0) continue;
            int rr = r + d[0] * off, cc = c + d[1] * off; if (!in(rr, cc)) continue;
            int e = idx(rr, cc); if (board[e] != 0) continue;
            board[e] = 1;
            boolean five = runLen(cell, 1, d) >= 5 || runLen(e, 1, d) >= 5;
            board[e] = 0;
            if (five) return true;
        }
        return false;
    }

    /** 이 방향이 '열린 3'인가: 빈칸 하나 채우면 열린 4(_BBBB_)가 되는가. */
    private boolean openThreeInDir(int cell, int[] d) {
        int r = cell / N, c = cell % N;
        for (int off = -4; off <= 4; off++) {
            if (off == 0) continue;
            int rr = r + d[0] * off, cc = c + d[1] * off; if (!in(rr, cc)) continue;
            int e = idx(rr, cc); if (board[e] != 0) continue;
            board[e] = 1;
            boolean of = isOpenFour(cell, d) || isOpenFour(e, d);
            board[e] = 0;
            if (of) return true;
        }
        return false;
    }

    /** anchor를 포함한 이 방향 연속 4가 양끝 빈칸(열린 4)인가. */
    private boolean isOpenFour(int anchor, int[] d) {
        int r = anchor / N, c = anchor % N;
        int fwd = 0; for (int s = 1; s < 6; s++) { int rr = r + d[0] * s, cc = c + d[1] * s; if (in(rr, cc) && board[idx(rr, cc)] == 1) fwd++; else break; }
        int bwd = 0; for (int s = 1; s < 6; s++) { int rr = r - d[0] * s, cc = c - d[1] * s; if (in(rr, cc) && board[idx(rr, cc)] == 1) bwd++; else break; }
        if (1 + fwd + bwd != 4) return false;
        int er = r + d[0] * (fwd + 1), ec = c + d[1] * (fwd + 1);
        int br = r - d[0] * (bwd + 1), bc = c - d[1] * (bwd + 1);
        return in(er, ec) && board[idx(er, ec)] == 0 && in(br, bc) && board[idx(br, bc)] == 0;
    }

    // =================== 봇 ===================

    private int botMove(int color, String level) {
        int opp = 3 - color;
        List<Integer> cands = candidates();
        if (cands.isEmpty()) { int c = idx(7, 7); return board[c] == 0 ? c : -1; }

        // 1) 즉시 승리
        for (int m : cands) if (canPlace(m, color) && makesWin(m, color)) return m;
        // 2) 상대 즉시 승리 차단
        List<Integer> oppWin = new ArrayList<>();
        for (int m : cands) if (makesWin(m, opp)) oppWin.add(m);
        if (!oppWin.isEmpty()) for (int m : oppWin) if (canPlace(m, color)) return m;

        // 3) 휴리스틱
        double atk = level.equals("EASY") ? 1.0 : 1.0, def = level.equals("EASY") ? 0.5 : level.equals("HARD") ? 1.1 : 0.9;
        int best = -1; double bestScore = -1;
        List<int[]> scored = new ArrayList<>();
        for (int m : cands) {
            if (!canPlace(m, color)) continue;
            double my = scoreCell(m, color), op = scoreCell(m, opp);
            double sc = my * atk + op * def;
            if (level.equals("EASY")) sc += ThreadLocalRandom.current().nextDouble() * 200; // 약하게 흔들기
            if (sc > bestScore) { bestScore = sc; best = m; }
            scored.add(new int[]{ m, (int) Math.min(sc, Integer.MAX_VALUE) });
        }
        if (level.equals("HARD") && !scored.isEmpty()) {
            scored.sort((a, b) -> Integer.compare(b[1], a[1]));
            int k = Math.min(8, scored.size());
            double hb = -1e18; int hbest = best;
            for (int i = 0; i < k; i++) {
                int m = scored.get(i)[0]; if (!canPlace(m, color)) continue;
                board[m] = color;
                double oppBest = 0;
                for (int o : candidates()) if (canPlace(o, opp)) oppBest = Math.max(oppBest, scoreCell(o, opp));
                double val = scoreCell(m, color) * 1.0 - oppBest * 1.0;
                board[m] = 0;
                if (val > hb) { hb = val; hbest = m; }
            }
            return hbest;
        }
        return best;
    }

    private boolean canPlace(int cell, int color) { return board[cell] == 0 && !isForbidden(cell, color); }
    private boolean makesWin(int cell, int color) {
        if (board[cell] != 0) return false;
        board[cell] = color; boolean w = winningLine(cell, color) != null; board[cell] = 0; return w;
    }

    /** cell에 color를 두었을 때 4방향 윈도 점수 합(공격/수비 공용). */
    private long scoreCell(int cell, int color) {
        long s = 0;
        for (int[] d : DIRS) s += evalDir(cell, color, d);
        return s;
    }
    private long evalDir(int cell, int color, int[] d) {
        int r = cell / N, c = cell % N, opp = 3 - color; long s = 0;
        for (int off = -4; off <= 0; off++) {
            int my = 0, bad = 0; boolean valid = true;
            for (int j = 0; j < 5; j++) {
                int rr = r + d[0] * (off + j), cc = c + d[1] * (off + j);
                if (!in(rr, cc)) { valid = false; break; }
                int v = idx(rr, cc) == cell ? color : board[idx(rr, cc)];
                if (v == color) my++; else if (v == opp) { bad = 1; break; }
            }
            if (valid && bad == 0) s += WEIGHT[my];
        }
        return s;
    }

    /** 기존 돌 주변(거리 2) 빈칸 후보. */
    private List<Integer> candidates() {
        List<Integer> out = new ArrayList<>();
        boolean any = false;
        boolean[] mark = new boolean[board.length];
        for (int i = 0; i < board.length; i++) if (board[i] != 0) {
            any = true; int r = i / N, c = i % N;
            for (int dr = -2; dr <= 2; dr++) for (int dc = -2; dc <= 2; dc++) {
                int rr = r + dr, cc = c + dc; if (in(rr, cc) && board[idx(rr, cc)] == 0 && !mark[idx(rr, cc)]) { mark[idx(rr, cc)] = true; out.add(idx(rr, cc)); }
            }
        }
        if (!any) out.add(idx(7, 7));
        return out;
    }

    // =================== 응답 ===================

    private OmokStateResponse build(String clientId) {
        long now = System.currentTimeMillis();
        if (phase == null) return OmokStateResponse.notStarted(now);
        Integer mySeat = seats.get(clientId);
        boolean joined = mySeat != null;
        int myColor = joined ? players.get(mySeat).color : 0;
        boolean myTurn = joined && phase == Phase.PLAYING && myColor == currentColor;

        List<PlayerView> pv = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) { Player p = players.get(i); pv.add(new PlayerView(i + 1, p.nick, p.ai, p.color)); }
        List<Integer> bl = new ArrayList<>(board.length);
        for (int v : board) bl.add(v);
        List<Integer> forb = new ArrayList<>();
        if (myTurn && myColor == 1 && "RENJU".equals(rule))
            for (int i = 0; i < board.length; i++) if (board[i] == 0 && isForbidden(i, 1)) forb.add(i);
        List<Integer> wl = new ArrayList<>(); for (int v : winLine) wl.add(v);

        return new OmokStateResponse(
                phase.name(), now, clientId.equals(hostClientId), joined,
                joined ? mySeat + 1 : 0, joined ? players.get(mySeat).nick : null,
                myColor, hostColor, rule, pv, bl, phase == Phase.PLAYING ? currentColor : 0,
                myTurn, lastMove, winner, wl, forb, lastAction, players.size(), version);
    }

    // =================== RoomGame ===================

    @Override public synchronized String roomStatus() { if (phase == null || phase == Phase.LOBBY) return "WAITING"; return phase == Phase.ENDED ? "ENDED" : "PLAYING"; }
    @Override public synchronized int playerCount() { return (int) players.stream().filter(p -> !p.ai && !leftClients.contains(p.clientId)).count(); }
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
            if (phase == Phase.PLAYING) { // 나간 사람 패배 → 상대 승리
                Player left = players.get(seat);
                for (Player p : players) if (p != left && !p.ai) { winner = p.color; phase = Phase.ENDED; lastAction = left.nick + "님 나감 · " + p.nick + " 승리"; return; }
                winner = 3 - left.color; phase = Phase.ENDED; lastAction = left.nick + "님 나감";
            }
        }
    }

    // 테스트용
    int[] boardForTest() { return board; }
    int winnerForTest() { return winner; }
    boolean forbiddenForTest(int cell) { return isForbidden(cell, 1); }
    void setRuleForTest(String r) { rule = r; }
    Phase phaseForTest() { return phase; }
    void forceBotNowForTest() { botActAt = 0; }

    // =================== 유틸 ===================

    private long botDelay() { return 500 + ThreadLocalRandom.current().nextInt(600); }
    private static boolean in(int r, int c) { return r >= 0 && r < N && c >= 0 && c < N; }
    private static int idx(int r, int c) { return r * N + c; }
    private static String coord(int cell) { return "" + (char) ('A' + cell % N) + (cell / N + 1); }
    private boolean isBoardFull() { for (int v : board) if (v == 0) return false; return true; }
    private Player seatColor(int color) { for (Player p : players) if (p.color == color) return p; return null; }
    private void addPlayer(String clientId, String nick) { seats.put(clientId, players.size()); players.add(new Player(clientId, trimNick(nick))); }
    private Player requirePlayer(String clientId) { Integer s = seats.get(clientId); if (s == null) throw bad("참가하지 않은 기기입니다"); return players.get(s); }
    private void touch() { version++; lastActiveMs = System.currentTimeMillis(); }
    private void reset() {
        phase = null; hostClientId = null; players.clear(); seats.clear(); leftClients.clear();
        rule = "FREE"; hostColor = 1; for (int i = 0; i < board.length; i++) board[i] = 0;
        currentColor = 1; lastMove = -1; lastAction = null; winner = 0; winLine = new int[0]; botActAt = 0; version = 0;
    }
    private static String normalizeLevel(String s) { if (s == null) return "NORMAL"; String u = s.toUpperCase(); return (u.equals("EASY") || u.equals("HARD")) ? u : "NORMAL"; }
    private static String levelLabel(String lvl) { return switch (lvl) { case "EASY" -> "초급"; case "HARD" -> "고급"; default -> "중급"; }; }
    private static BusinessException bad(String msg) { return new BusinessException(ErrorCode.INVALID_INPUT, msg); }
    private static String trimNick(String nick) { String t = nick == null ? "" : nick.trim(); if (t.isEmpty()) t = "익명"; return t.length() > 16 ? t.substring(0, 16) : t; }
}
