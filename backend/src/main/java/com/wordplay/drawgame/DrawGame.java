package com.wordplay.drawgame;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.drawgame.dto.DrawGameStateResponse;
import com.wordplay.drawgame.dto.DrawGameStateResponse.AlbumView;
import com.wordplay.drawgame.dto.DrawGameStateResponse.PlayerView;
import com.wordplay.drawgame.dto.DrawGameStateResponse.StepView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 그림 게임 방(인메모리). 현재 모드: GARTIC(갈틱폰=그림 텔레폰).
 * 각자 완성 그림/문장만 제출하는 턴제라 실시간 스트리밍이 필요 없다.
 *
 * 진행: N명 → 앨범 N권(각자 1권). 라운드마다 앨범이 옆으로 넘어가고,
 * 받은 문장을 그림으로 / 받은 그림을 문장으로 번갈아 표현. N라운드 후 전체 공개.
 * topicMode=FREE: 0라운드에 각자 시작 문장 입력. RANDOM: 서버가 제시어 자동 배정.
 */
public class DrawGame implements RoomGame {

    enum Phase { LOBBY, PLAYING, REVEAL }
    enum Mode { GARTIC, CATCHMIND }

    static final String TEXT = "TEXT", IMAGE = "IMAGE";

    static final class Step {
        final String type;      // TEXT / IMAGE
        final String content;   // 문장 또는 data URL
        final int authorSeat;
        Step(String type, String content, int authorSeat) { this.type = type; this.content = content; this.authorSeat = authorSeat; }
    }
    static final class Album {
        final int owner;
        final List<Step> steps = new ArrayList<>();
        Album(int owner) { this.owner = owner; }
    }
    static final class Player {
        final String clientId; String nick;
        Player(String clientId, String nick) { this.clientId = clientId; this.nick = nick; }
    }

    private static final String[] RANDOM_WORDS = {
            "고양이", "피자 먹는 강아지", "우주에서 춤추는 로봇", "바나나를 든 원숭이", "비 오는 날의 우산",
            "왕관 쓴 개구리", "축구하는 펭귄", "선글라스 낀 상어", "케이크를 든 공룡", "하늘을 나는 자동차",
            "책 읽는 부엉이", "낚시하는 곰", "무지개 아이스크림", "슈퍼히어로 고슴도치", "커피 마시는 판다",
            "롤러스케이트 타는 토끼", "기타 치는 문어", "눈사람과 눈싸움", "요리하는 여우", "잠자는 나무늘보",
    };

    private Phase phase = null;
    private Mode mode = Mode.GARTIC;
    private String topicMode = "FREE"; // FREE / RANDOM
    private long lastActiveMs = System.currentTimeMillis();
    private String hostClientId = null;
    private final List<Player> players = new ArrayList<>();
    private final Map<String, Integer> seats = new HashMap<>();

    private final List<Album> albums = new ArrayList<>();
    private int round = 0;
    private int totalRounds = 0;
    private long deadline = 0;
    private final Set<Integer> submitted = new HashSet<>();
    private long version = 0;

    private long writeMs = 60_000, drawMs = 150_000;

    // =================== 명령 ===================

    public synchronized DrawGameStateResponse newGame(String clientId, String nick, String mode, String topicMode,
                                                      Integer writeSec, Integer drawSec) {
        reset();
        phase = Phase.LOBBY;
        this.mode = "CATCHMIND".equalsIgnoreCase(mode) ? Mode.CATCHMIND : Mode.GARTIC;
        this.topicMode = "RANDOM".equalsIgnoreCase(topicMode) ? "RANDOM" : "FREE";
        this.writeMs = clampSec(writeSec, 20, 180, 60) * 1000L;
        this.drawMs = clampSec(drawSec, 30, 300, 150) * 1000L;
        hostClientId = clientId;
        addPlayer(clientId, nick);
        touch();
        return me(clientId);
    }

    public synchronized DrawGameStateResponse join(String clientId, String nick) {
        if (phase == null) throw bad("생성된 방이 없습니다");
        if (phase != Phase.LOBBY) throw bad("이미 진행 중이라 참가할 수 없습니다");
        if (!seats.containsKey(clientId)) {
            if (players.size() >= 10) throw bad("정원(10명)이 찼습니다");
            addPlayer(clientId, nick);
        } else {
            players.get(seats.get(clientId)).nick = trimNick(nick);
        }
        touch();
        return me(clientId);
    }

    public synchronized DrawGameStateResponse start(String clientId) {
        if (phase != Phase.LOBBY) throw bad("지금 시작할 수 없습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 시작할 수 있습니다");
        if (mode == Mode.CATCHMIND) throw bad("캐치마인드 모드는 곧 추가됩니다");
        if (players.size() < 3) throw bad("최소 3명이 필요합니다");

        int n = players.size();
        albums.clear();
        for (int i = 0; i < n; i++) albums.add(new Album(i));
        totalRounds = n;
        submitted.clear();

        if (topicMode.equals("RANDOM")) {
            // 0라운드(시작 문장) 자동 배정 후 1라운드(그리기)부터
            List<String> pool = new ArrayList<>(List.of(RANDOM_WORDS));
            Collections.shuffle(pool);
            for (int i = 0; i < n; i++) albums.get(i).steps.add(new Step(TEXT, pool.get(i % pool.size()), i));
            round = 1;
        } else {
            round = 0; // 각자 시작 문장 입력
        }
        phase = Phase.PLAYING;
        deadline = System.currentTimeMillis() + deadlineFor(round);
        touch();
        return me(clientId);
    }

    /** 이번 라운드 내 앨범에 제출(텍스트 또는 그림). */
    public synchronized DrawGameStateResponse submit(String clientId, String type, String content) {
        tick();
        Integer seat = seats.get(clientId);
        if (seat == null) throw bad("참가하지 않은 기기입니다");
        if (phase != Phase.PLAYING) throw bad("지금은 제출할 수 없습니다");
        String need = stepType(round);
        if (!need.equals(type)) throw bad("이번 라운드 제출 형식이 올바르지 않습니다");
        if (IMAGE.equals(type)) {
            if (content == null || !content.startsWith("data:image")) throw bad("그림 데이터가 올바르지 않습니다");
            if (content.length() > 700_000) throw bad("그림이 너무 큽니다");
        } else {
            content = content == null ? "" : content.trim();
            if (content.length() > 100) content = content.substring(0, 100);
            if (content.isEmpty()) content = "(비어있음)";
        }
        int a = albumOf(seat, round);
        Album album = albums.get(a);
        if (album.steps.size() > round) album.steps.set(round, new Step(type, content, seat));
        else album.steps.add(new Step(type, content, seat));
        submitted.add(seat);
        maybeAdvance();
        touch();
        return me(clientId);
    }

    public synchronized DrawGameStateResponse me(String clientId) {
        lastActiveMs = System.currentTimeMillis();
        tick();
        return build(clientId);
    }

    public synchronized DrawGameStateResponse resetGame() {
        reset();
        return DrawGameStateResponse.notStarted(System.currentTimeMillis());
    }

    // =================== 진행 ===================

    private void tick() {
        if (phase != Phase.PLAYING) return;
        if (deadline > 0 && System.currentTimeMillis() >= deadline) {
            // 미제출자 자동 채움(빈 값)
            for (int p = 0; p < players.size(); p++) {
                if (submitted.contains(p)) continue;
                int a = albumOf(p, round);
                String t = stepType(round);
                Step s = new Step(t, IMAGE.equals(t) ? "" : "(시간 초과)", p);
                if (albums.get(a).steps.size() > round) albums.get(a).steps.set(round, s);
                else albums.get(a).steps.add(s);
                submitted.add(p);
            }
            maybeAdvance();
        }
    }

    private void maybeAdvance() {
        if (submitted.size() < players.size()) return;
        round++;
        submitted.clear();
        if (round >= totalRounds) {
            phase = Phase.REVEAL;
            deadline = 0;
        } else {
            deadline = System.currentTimeMillis() + deadlineFor(round);
        }
    }

    /** round 0 = 텍스트, 이후 홀수=그림 / 짝수=텍스트. */
    private String stepType(int r) { return r == 0 ? TEXT : (r % 2 == 1 ? IMAGE : TEXT); }

    private long deadlineFor(int r) { return IMAGE.equals(stepType(r)) ? drawMs : writeMs; }

    /** 라운드 r에서 플레이어 p가 들고 있는 앨범 index. */
    private int albumOf(int p, int r) {
        int n = players.size();
        return ((p - r) % n + n) % n;
    }

    // =================== 응답 ===================

    private DrawGameStateResponse build(String clientId) {
        long now = System.currentTimeMillis();
        if (phase == null) return DrawGameStateResponse.notStarted(now);

        Integer mySeat = seats.get(clientId);
        boolean joined = mySeat != null;

        List<PlayerView> pv = new ArrayList<>();
        for (int i = 0; i < players.size(); i++)
            pv.add(new PlayerView(i + 1, players.get(i).nick, submitted.contains(i)));

        String taskType = null, promptText = null, promptImage = null;
        boolean mySubmitted = false;
        if (phase == Phase.PLAYING && joined) {
            mySubmitted = submitted.contains(mySeat);
            String need = stepType(round);
            if (round == 0) {
                taskType = "WRITE_INITIAL";
            } else {
                int a = albumOf(mySeat, round);
                Step prev = albums.get(a).steps.get(round - 1);
                if (IMAGE.equals(need)) { taskType = "DRAW"; promptText = prev.content; }
                else { taskType = "WRITE"; promptImage = prev.content; }
            }
        }

        List<AlbumView> albumViews = new ArrayList<>();
        if (phase == Phase.REVEAL) {
            for (Album al : albums) {
                List<StepView> sv = new ArrayList<>();
                for (Step s : al.steps)
                    sv.add(new StepView(s.type, s.content, players.get(s.authorSeat).nick));
                albumViews.add(new AlbumView(players.get(al.owner).nick, sv));
            }
        }

        return new DrawGameStateResponse(
                phase.name(), mode.name(), topicMode, now,
                clientId.equals(hostClientId), joined,
                joined ? mySeat + 1 : 0, joined ? players.get(mySeat).nick : null,
                pv, round, totalRounds,
                taskType, promptText, promptImage, mySubmitted,
                submitted.size(), deadline, albumViews, players.size(), version
        );
    }

    // =================== RoomGame ===================

    @Override public synchronized String roomStatus() {
        if (phase == null || phase == Phase.LOBBY) return "WAITING";
        return phase == Phase.REVEAL ? "ENDED" : "PLAYING";
    }
    @Override public synchronized int playerCount() { return players.size(); }
    @Override public synchronized String hostLabel() { return players.isEmpty() ? "" : players.get(0).nick; }
    @Override public synchronized boolean isEnded() { return phase == Phase.REVEAL; }
    @Override public synchronized long lastActiveMs() { return lastActiveMs; }

    // =================== 유틸 ===================

    private void touch() { version++; lastActiveMs = System.currentTimeMillis(); }

    private void addPlayer(String clientId, String nick) {
        seats.put(clientId, players.size());
        players.add(new Player(clientId, trimNick(nick)));
    }

    private void reset() {
        phase = null; mode = Mode.GARTIC; topicMode = "FREE";
        hostClientId = null; players.clear(); seats.clear();
        albums.clear(); round = 0; totalRounds = 0; deadline = 0; submitted.clear(); version = 0;
    }

    private static BusinessException bad(String msg) { return new BusinessException(ErrorCode.INVALID_INPUT, msg); }

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
