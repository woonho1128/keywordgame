package com.wordplay.codenames;

import com.wordplay.codenames.dto.CodenamesStateResponse;
import com.wordplay.codenames.dto.CodenamesStateResponse.Cell;
import com.wordplay.codenames.dto.CodenamesStateResponse.PlayerView;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 코드네임 한 방(인메모리). 5×5 단어판, 2팀(RED/BLUE), 팀장 힌트 → 요원 추측.
 * 타이머 없음(행동으로만 진행).
 */
public class CodenamesGame implements RoomGame {

    enum Phase { LOBBY, CLUE, GUESS, ENDED }

    private static final class Player {
        final String clientId;
        String nick;
        String team;            // RED / BLUE / null
        boolean spymaster;
        Player(String clientId, String nick) { this.clientId = clientId; this.nick = nick; }
    }

    private static final class BoardCell {
        final String word;
        String color;           // RED/BLUE/NEUTRAL/ASSASSIN
        boolean revealed;
        BoardCell(String word) { this.word = word; }
    }

    private Phase phase = null;
    private long lastActiveMs = System.currentTimeMillis();
    private String hostClientId = null;
    private final List<Player> players = new ArrayList<>();
    private final Map<String, Integer> clientSeats = new HashMap<>();
    private final java.util.Set<String> leftClients = new java.util.HashSet<>();

    private final List<BoardCell> board = new ArrayList<>();
    private String startTeam = null, currentTeam = null;
    private String clueWord = null;
    private int clueNumber = 0, guessesLeft = 0;
    private int redRemaining = 0, blueRemaining = 0;
    private String winner = null, winReason = null;

    // =================== 명령 ===================

    public synchronized CodenamesStateResponse newGame(String clientId, String nick) {
        reset();
        phase = Phase.LOBBY;
        hostClientId = clientId;
        addPlayer(clientId, nick);
        return me(clientId);
    }

    public synchronized CodenamesStateResponse join(String clientId, String nick) {
        if (phase == null) throw bad("생성된 방이 없습니다");
        if (phase != Phase.LOBBY) throw bad("이미 진행 중이라 참가할 수 없습니다");
        if (!clientSeats.containsKey(clientId)) {
            if (players.size() >= 12) throw bad("정원(12명)이 찼습니다");
            addPlayer(clientId, nick);
        } else {
            players.get(clientSeats.get(clientId)).nick = trimNick(nick);
        }
        return me(clientId);
    }

    /** 팀 선택(대기방). 팀을 바꾸면 팀장 자격은 해제. */
    public synchronized CodenamesStateResponse setTeam(String clientId, String team) {
        Player me = requirePlayer(clientId);
        if (phase != Phase.LOBBY) throw bad("대기방에서만 팀을 정할 수 있습니다");
        if (!"RED".equals(team) && !"BLUE".equals(team)) throw bad("팀은 RED/BLUE만 가능합니다");
        me.team = team;
        me.spymaster = false;
        return me(clientId);
    }

    /** 팀장 지원(자기 팀에서 1명). 기존 팀장은 해제. */
    public synchronized CodenamesStateResponse claimSpymaster(String clientId) {
        Player me = requirePlayer(clientId);
        if (phase != Phase.LOBBY) throw bad("대기방에서만 정할 수 있습니다");
        if (me.team == null) throw bad("먼저 팀을 선택하세요");
        for (Player p : players) if (me.team.equals(p.team)) p.spymaster = false;
        me.spymaster = true;
        return me(clientId);
    }

    /** 랜덤 배정(방장). 인원을 두 팀으로 나누고 각 팀 첫 명을 팀장으로. */
    public synchronized CodenamesStateResponse randomAssign(String clientId) {
        if (phase != Phase.LOBBY) throw bad("대기방에서만 배정할 수 있습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 배정할 수 있습니다");
        List<Player> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);
        int half = shuffled.size() / 2;
        for (int i = 0; i < shuffled.size(); i++) {
            Player p = shuffled.get(i);
            p.team = i < half ? "RED" : "BLUE";
            p.spymaster = false;
        }
        // 각 팀 첫 명을 팀장으로
        boolean redSpy = false, blueSpy = false;
        for (Player p : shuffled) {
            if ("RED".equals(p.team) && !redSpy) { p.spymaster = true; redSpy = true; }
            if ("BLUE".equals(p.team) && !blueSpy) { p.spymaster = true; blueSpy = true; }
        }
        return me(clientId);
    }

    public synchronized CodenamesStateResponse start(String clientId) {
        if (phase != Phase.LOBBY) throw bad("지금 시작할 수 없습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 시작할 수 있습니다");
        for (String t : List.of("RED", "BLUE")) {
            long total = players.stream().filter(p -> t.equals(p.team)).count();
            long spy = players.stream().filter(p -> t.equals(p.team) && p.spymaster).count();
            if (total < 2) throw bad((t.equals("RED") ? "레드" : "블루") + " 팀은 최소 2명(팀장+요원)이 필요합니다");
            if (spy != 1) throw bad((t.equals("RED") ? "레드" : "블루") + " 팀 팀장을 1명 정하세요");
        }

        // 단어판 생성
        List<String> words = CodenamesWords.pick(25);
        startTeam = ThreadLocalRandom.current().nextBoolean() ? "RED" : "BLUE";
        String other = other(startTeam);
        List<String> colors = new ArrayList<>();
        for (int i = 0; i < 9; i++) colors.add(startTeam);
        for (int i = 0; i < 8; i++) colors.add(other);
        for (int i = 0; i < 7; i++) colors.add("NEUTRAL");
        colors.add("ASSASSIN");
        Collections.shuffle(colors);

        board.clear();
        for (int i = 0; i < 25; i++) {
            BoardCell c = new BoardCell(words.get(i));
            c.color = colors.get(i);
            board.add(c);
        }
        redRemaining = (int) board.stream().filter(c -> "RED".equals(c.color)).count();
        blueRemaining = (int) board.stream().filter(c -> "BLUE".equals(c.color)).count();
        currentTeam = startTeam;
        clueWord = null;
        clueNumber = guessesLeft = 0;
        winner = winReason = null;
        phase = Phase.CLUE;
        return me(clientId);
    }

    /** 팀장 힌트 제출. */
    public synchronized CodenamesStateResponse clue(String clientId, String word, int number) {
        Player me = requirePlayer(clientId);
        if (phase != Phase.CLUE) throw bad("지금은 힌트 단계가 아닙니다");
        if (!me.spymaster || !me.team.equals(currentTeam)) throw bad("지금 팀의 팀장만 힌트를 줄 수 있습니다");
        String w = word == null ? "" : word.trim();
        if (w.isEmpty() || w.length() > 20) throw bad("힌트 단어를 확인하세요");
        if (number < 1 || number > 9) throw bad("숫자는 1~9로 입력하세요");
        clueWord = w;
        clueNumber = number;
        guessesLeft = number + 1;
        phase = Phase.GUESS;
        return me(clientId);
    }

    /** 요원 추측. */
    public synchronized CodenamesStateResponse guess(String clientId, int index) {
        Player me = requirePlayer(clientId);
        if (phase != Phase.GUESS) throw bad("지금은 추측 단계가 아닙니다");
        if (me.spymaster || !currentTeam.equals(me.team)) throw bad("지금 팀의 요원만 추측할 수 있습니다");
        if (index < 0 || index >= board.size()) throw bad("잘못된 칸입니다");
        BoardCell cell = board.get(index);
        if (cell.revealed) throw bad("이미 공개된 칸입니다");

        cell.revealed = true;
        String c = cell.color;
        if ("ASSASSIN".equals(c)) { endGame(other(currentTeam), "암살자 카드를 건드렸습니다"); return me(clientId); }
        if ("RED".equals(c)) redRemaining--;
        if ("BLUE".equals(c)) blueRemaining--;
        if (redRemaining == 0) { endGame("RED", "레드가 모든 단어를 공개했습니다"); return me(clientId); }
        if (blueRemaining == 0) { endGame("BLUE", "블루가 모든 단어를 공개했습니다"); return me(clientId); }

        if (c.equals(currentTeam)) {
            guessesLeft--;
            if (guessesLeft <= 0) endTurn();
        } else {
            endTurn(); // 중립 또는 상대 팀 카드
        }
        return me(clientId);
    }

    /** 요원이 턴 넘기기. */
    public synchronized CodenamesStateResponse pass(String clientId) {
        Player me = requirePlayer(clientId);
        if (phase != Phase.GUESS) throw bad("지금은 추측 단계가 아닙니다");
        if (me.spymaster || !currentTeam.equals(me.team)) throw bad("지금 팀의 요원만 넘길 수 있습니다");
        endTurn();
        return me(clientId);
    }

    public synchronized CodenamesStateResponse resetGame() {
        reset();
        return CodenamesStateResponse.notStarted(System.currentTimeMillis());
    }

    public synchronized CodenamesStateResponse me(String clientId) {
        lastActiveMs = System.currentTimeMillis();
        return buildResponse(clientId);
    }

    // =================== 진행 ===================

    private void endTurn() {
        currentTeam = other(currentTeam);
        clueWord = null;
        clueNumber = guessesLeft = 0;
        phase = Phase.CLUE;
    }

    private void endGame(String w, String reason) {
        winner = w;
        winReason = reason;
        phase = Phase.ENDED;
    }

    private static String other(String team) { return "RED".equals(team) ? "BLUE" : "RED"; }

    // =================== 응답 ===================

    private CodenamesStateResponse buildResponse(String clientId) {
        long now = System.currentTimeMillis();
        if (phase == null) return CodenamesStateResponse.notStarted(now);

        boolean ended = phase == Phase.ENDED;
        Integer mySeat = clientSeats.get(clientId);
        Player me = mySeat == null ? null : players.get(mySeat);
        boolean joined = me != null;
        boolean spy = joined && me.spymaster;

        List<PlayerView> pv = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            pv.add(new PlayerView(i + 1, p.nick, p.team, p.spymaster));
        }

        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < board.size(); i++) {
            BoardCell c = board.get(i);
            String color = (c.revealed || spy || ended) ? c.color : null;
            cells.add(new Cell(i, c.word, c.revealed, color));
        }

        boolean activeSpy = joined && phase == Phase.CLUE && me.spymaster && me.team != null && me.team.equals(currentTeam);
        boolean activeOp = joined && phase == Phase.GUESS && !me.spymaster && me.team != null && me.team.equals(currentTeam);

        return new CodenamesStateResponse(
                phase.name(),
                now,
                clientId.equals(hostClientId),
                joined,
                joined ? mySeat + 1 : 0,
                joined ? me.nick : null,
                joined ? me.team : null,
                spy,
                pv,
                cells,
                currentTeam,
                startTeam,
                clueWord,
                clueNumber,
                guessesLeft,
                redRemaining,
                blueRemaining,
                activeSpy,
                activeOp,
                ended ? winner : null,
                ended ? winReason : null,
                players.size()
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

    private void addPlayer(String clientId, String nick) {
        clientSeats.put(clientId, players.size());
        players.add(new Player(clientId, trimNick(nick)));
    }

    private Player requirePlayer(String clientId) {
        Integer s = clientSeats.get(clientId);
        if (s == null) throw bad("참가하지 않은 기기입니다");
        return players.get(s);
    }

    private void reset() {
        phase = null;
        hostClientId = null;
        players.clear();
        clientSeats.clear();
        leftClients.clear();
        board.clear();
        startTeam = currentTeam = null;
        clueWord = null;
        clueNumber = guessesLeft = 0;
        redRemaining = blueRemaining = 0;
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
