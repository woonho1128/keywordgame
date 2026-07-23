package com.wordplay.monopoly;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.monopoly.dto.MonopolyState;
import com.wordplay.monopoly.dto.MonopolyState.Card;
import com.wordplay.monopoly.dto.MonopolyState.Pending;
import com.wordplay.monopoly.dto.MonopolyState.PlayerView;
import com.wordplay.monopoly.dto.MonopolyState.TileView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 부루마블(모두의마블 느낌) 멀티플레이 방. 2~4인(+봇).
 *
 * 32칸(코너 4 + 각 변 7). 주사위 2개·더블·무인도·세계여행·올림픽(축제)·국세청·사회복지기금·황금열쇠.
 * 도시 구매→건물(별장·빌딩·호텔·랜드마크)→통행료. 같은 색 라인 독점 시 통행료 2배.
 * 남의 도시 도착 시 통행료 대신 2배 값에 인수 가능. 파산하면 탈락, 마지막 1인 승리.
 * 카드(황금열쇠)는 뽑은 본인 화면에만 내용 공개.
 */
public class MonopolyGame implements RoomGame {

    public enum Phase { LOBBY, PLAYING, ENDED }
    public enum Step { ROLL, DECIDE }

    static final int MAX_PLAYERS = 4;
    static final long TURN_MS = 45_000, BOT_DELAY_MS = 2000; // 봇 한 동작 간 간격(느긋하게)
    static final long START_CASH = 1500, SALARY = 300;
    static final int ISLAND = 8, TRAVEL = 24, OLYMPIC = 16, START_TILE = 0;
    static final int MAX_ISLAND_TURNS = 3;

    // ── 보드(프론트와 동일) ──
    record Tile(String name, String type, String group, int price) {}
    static final Tile[] BOARD = {
            new Tile("출발", "START", null, 0),
            new Tile("수원", "CITY", "A", 50),
            new Tile("성남", "CITY", "A", 60),
            new Tile("황금열쇠", "GOLDKEY", null, 0),
            new Tile("인천", "CITY", "A", 80),
            new Tile("대전", "CITY", "B", 90),
            new Tile("국세청", "TAX", null, 0),
            new Tile("광주", "CITY", "B", 100),
            new Tile("무인도", "ISLAND", null, 0),
            new Tile("대구", "CITY", "B", 110),
            new Tile("울산", "CITY", "C", 130),
            new Tile("창원", "CITY", "C", 140),
            new Tile("황금열쇠", "GOLDKEY", null, 0),
            new Tile("청주", "CITY", "C", 150),
            new Tile("전주", "CITY", "D", 170),
            new Tile("천안", "CITY", "D", 180),
            new Tile("올림픽", "FESTIVAL", null, 0),
            new Tile("고양", "CITY", "D", 200),
            new Tile("황금열쇠", "GOLDKEY", null, 0),
            new Tile("용인", "CITY", "E", 220),
            new Tile("포항", "CITY", "E", 240),
            new Tile("사회복지기금", "FUND", null, 0),
            new Tile("부산", "CITY", "E", 270),
            new Tile("제주", "CITY", "F", 290),
            new Tile("세계여행", "TRAVEL", null, 0),
            new Tile("강릉", "CITY", "F", 310),
            new Tile("경주", "CITY", "F", 330),
            new Tile("황금열쇠", "GOLDKEY", null, 0),
            new Tile("여수", "CITY", "G", 360),
            new Tile("김해", "CITY", "G", 380),
            new Tile("서울", "CITY", "G", 420),
            new Tile("파주", "CITY", "G", 500),
    };
    static final int N = BOARD.length;

    // 건물 단계별 통행료 배율(가격 대비), 건설비 배율(다음 단계로)
    static final double[] TOLL_MUL = {0.10, 0.45, 1.1, 2.2, 4.5};   // 0땅 1별장 2빌딩 3호텔 4랜드마크
    static final double[] BUILD_MUL = {0.5, 0.6, 0.7, 1.0};          // t→t+1 비용

    static final class P {
        String clientId, nick, botLevel;
        boolean bot, host, left;
        int color, seat, team;
        long cash = START_CASH;
        int pos = 0;
        boolean alive = true, inIsland = false;
        int islandTurns = 0;
        boolean tollImmunity = false;
        long lastSeen;
    }

    // 카드(효과 즉시 적용). kind로 효과 분기.
    record Chance(String icon, String title, String desc, String kind, int amount) {}
    static final Chance[] CARDS = {
            new Chance("🎁", "보너스!", "은행에서 100만원을 받습니다.", "GAIN", 100),
            new Chance("💰", "은행 이자", "이자 60만원을 받습니다.", "GAIN", 60),
            new Chance("🎉", "생일 파티", "모두에게 30만원씩 받습니다.", "BIRTHDAY", 30),
            new Chance("✈️", "출발지로!", "출발 칸으로 이동하고 월급을 받습니다.", "TO_START", 0),
            new Chance("🏝️", "무인도행", "무인도로 끌려갑니다.", "TO_ISLAND", 0),
            new Chance("💸", "세금 고지서", "세금 80만원을 냅니다.", "PAY", 80),
            new Chance("🚓", "벌금 딱지", "벌금 60만원을 냅니다.", "PAY", 60),
            new Chance("🏠", "재산세", "보유 현금의 10%를 세금으로 냅니다.", "TAX_PCT", 10),
            new Chance("🎟️", "우대권", "다음 통행료 한 번을 면제받습니다.", "IMMUNITY", 0),
            new Chance("🎈", "복권 당첨", "150만원을 받습니다.", "GAIN", 150),
    };

    private final String hostClientId;
    private final boolean teamMode;
    private Phase phase = Phase.LOBBY;
    private Step step = Step.ROLL;
    private final List<P> players = new ArrayList<>();
    private final int[] owner = new int[N];   // 소유 seat, -1
    private final int[] tier = new int[N];    // 건물 단계 0~4
    private int festivalTile = -1;
    private long pot = 0;
    private int turnSeat = -1;
    private int[] dice = {0, 0};
    private boolean lastDouble = false;
    private int doublesCount = 0;
    private boolean extraRoll = false;

    // 결정 대기
    private String pendType = "NONE";
    private int pendTile = -1;
    private int pendCardOwner = -1;
    private Chance pendCard = null;
    private List<Integer> travelOptions = null;

    private int winnerSeat = -1;
    private String winnerLabel = null, lastAction = null;
    private final List<String> log = new ArrayList<>();
    private long turnEndsAt = 0, botAt = 0, lastActive = System.currentTimeMillis();

    public MonopolyGame(String hostClientId, String nick, boolean teamMode) {
        this.hostClientId = hostClientId;
        this.teamMode = teamMode;
        for (int i = 0; i < N; i++) { owner[i] = -1; tier[i] = 0; }
        P host = new P(); host.clientId = hostClientId; host.nick = clean(nick); host.host = true; host.lastSeen = now();
        players.add(host);
    }

    private void note(String s) { lastAction = s; log.add(s); if (log.size() > 40) log.remove(0); }

    // ── 로비 ──
    public synchronized void join(String clientId, String nick) {
        touch();
        P e = byClient(clientId);
        if (e != null) { e.left = false; e.nick = clean(nick); return; }
        if (phase != Phase.LOBBY) throw bad("이미 시작된 방입니다");
        if (activeCount() >= MAX_PLAYERS) throw bad("정원(4명)이 찼습니다");
        P p = new P(); p.clientId = clientId; p.nick = clean(nick); p.lastSeen = now();
        players.add(p);
    }
    public synchronized void addBot(String clientId, String level) {
        touch(); requireHost(clientId);
        if (phase != Phase.LOBBY) throw bad("이미 시작된 방입니다");
        if (activeCount() >= MAX_PLAYERS) throw bad("정원(4명)이 찼습니다");
        P b = new P(); b.bot = true; b.botLevel = normLevel(level); b.nick = botName();
        players.add(b);
    }
    public synchronized void start(String clientId) {
        touch(); requireHost(clientId);
        if (phase != Phase.LOBBY) throw bad("이미 시작되었습니다");
        if (activeCount() < 2) throw bad("최소 2명(봇 포함)이 필요합니다");
        if (teamMode && activeCount() != 4) throw bad("팀전은 4명이 필요합니다");
        for (int i = 0; i < players.size(); i++) {
            P p = players.get(i);
            p.seat = i; p.color = i; p.team = teamMode ? (i % 2) : i;
            p.cash = START_CASH; p.pos = 0;
            p.alive = true; p.inIsland = false; p.islandTurns = 0; p.tollImmunity = false;
        }
        phase = Phase.PLAYING;
        turnSeat = 0;
        beginTurn();
    }

    private void beginTurn() {
        step = Step.ROLL;
        doublesCount = 0; extraRoll = false; lastDouble = false;
        clearPending();
        turnEndsAt = now() + TURN_MS; botAt = now() + BOT_DELAY_MS;
    }
    private void clearPending() { pendType = "NONE"; pendTile = -1; pendCard = null; pendCardOwner = -1; travelOptions = null; }

    // ── 주사위 ──
    // power 0~120: 세기는 낙 개념 없이 단순히 눈 두 개를 굴린다(연출용). 더블이면 한 번 더.
    public synchronized void roll(String clientId, int power) {
        touch(); tick();
        requireTurn(clientId);
        if (step != Step.ROLL) throw bad("지금은 굴릴 수 없습니다");
        doRoll(players.get(turnSeat));
    }

    private void doRoll(P p) {
        int d1 = 1 + ThreadLocalRandom.current().nextInt(6), d2 = 1 + ThreadLocalRandom.current().nextInt(6);
        dice = new int[]{d1, d2};
        boolean dbl = d1 == d2;
        lastDouble = dbl;
        int steps = d1 + d2;

        if (p.inIsland) {
            if (dbl) { p.inIsland = false; p.islandTurns = 0; note(p.nick + " 더블! 무인도 탈출 🎉"); }
            else {
                p.islandTurns++;
                if (p.islandTurns >= MAX_ISLAND_TURNS) { p.inIsland = false; p.islandTurns = 0; note(p.nick + " 무인도 " + MAX_ISLAND_TURNS + "턴 경과 → 탈출"); }
                else { note(p.nick + " 🎲 " + d1 + "+" + d2 + " · 무인도 대기(" + p.islandTurns + "/" + MAX_ISLAND_TURNS + ")"); endTurn(); return; }
            }
            move(p, steps, false); // 탈출 후 이동(추가 던지기 없음)
            return;
        }

        if (dbl) {
            doublesCount++;
            if (doublesCount >= 3) { note(p.nick + " 더블 3연속 → 무인도! 🏝️"); sendToIsland(p); endTurn(); return; }
            extraRoll = true;
        }
        note(p.nick + " 🎲 " + d1 + "+" + d2 + " = " + steps + (dbl ? " ✨더블" : ""));
        move(p, steps, true);
    }

    private void move(P p, int steps, boolean allowExtra) {
        int from = p.pos;
        int to = (from + steps) % N;
        if (from + steps >= N) { p.cash += SALARY; note(p.nick + " 출발 통과 · 월급 +" + SALARY + "만"); }
        p.pos = to;
        if (!allowExtra) extraRoll = false;
        resolveLanding(p);
    }

    private void sendToIsland(P p) { p.pos = ISLAND; p.inIsland = true; p.islandTurns = 0; extraRoll = false; }

    // ── 착지 처리 ──
    private void resolveLanding(P p) {
        Tile t = BOARD[p.pos];
        switch (t.type()) {
            case "START" -> { p.cash += SALARY; note(p.nick + " 출발 도착 · 보너스 +" + SALARY + "만"); afterResolve(p); }
            case "ISLAND" -> { note(p.nick + " 무인도 도착 🏝️"); sendToIsland(p); endTurn(); }
            case "TAX" -> { long tax = Math.min(p.cash, Math.max(50, p.cash / 10)); p.cash -= tax; pot += tax; note(p.nick + " 국세청 · 세금 -" + tax + "만(기금 적립)"); afterResolve(p); }
            case "FUND" -> { long g = pot; pot = 0; p.cash += g; note(p.nick + " 사회복지기금 +" + g + "만 수령 🎁"); afterResolve(p); }
            case "FESTIVAL" -> beginOlympic(p);
            case "TRAVEL" -> beginTravel(p);
            case "GOLDKEY" -> drawCard(p);
            case "CITY" -> resolveCity(p);
            default -> afterResolve(p);
        }
    }

    private void beginOlympic(P p) {
        // 올림픽: 내가 가진 도시 중 하나를 골라 축제 개최(통행료 2배). 없으면 효과 없음.
        List<Integer> mine = new ArrayList<>();
        for (int i = 0; i < N; i++) if (owner[i] == p.seat) mine.add(i);
        if (mine.isEmpty()) { note(p.nick + " 올림픽 도착(개최할 도시 없음)"); afterResolve(p); return; }
        travelOptions = mine; pendType = "OLYMPIC"; pendTile = -1; step = Step.DECIDE;
        note(p.nick + " 올림픽 🏅 · 축제 개최할 내 도시 선택(통행료 2배)");
    }

    private void resolveCity(P p) {
        int i = p.pos; Tile t = BOARD[i];
        if (owner[i] == -1) {
            if (p.cash >= t.price()) { pendType = "BUY"; pendTile = i; step = Step.DECIDE; note(p.nick + " " + t.name() + " 구매 가능(" + t.price() + "만)"); }
            else { note(p.nick + " " + t.name() + " 도착(현금 부족, 구매 불가)"); afterResolve(p); }
        } else if (owner[i] == p.seat) {
            if (tier[i] < 4 && p.cash >= buildCost(i)) { pendType = "UPGRADE"; pendTile = i; step = Step.DECIDE; note(p.nick + " 내 도시 " + t.name() + " · 건설 가능(" + buildCost(i) + "만)"); }
            else { note(p.nick + " 내 도시 " + t.name() + " 도착"); afterResolve(p); }
        } else if (teamMode && players.get(owner[i]).team == p.team) {
            // 팀원 도시: 통행료 면제
            note(p.nick + " 팀원(" + players.get(owner[i]).nick + ") 도시 " + t.name() + " 도착 · 통행료 면제");
            afterResolve(p);
        } else {
            // 남의 도시: 통행료 or 인수
            long toll = tollOf(i);
            pendType = "TOLL"; pendTile = i; step = Step.DECIDE;
            note(p.nick + " " + t.name() + " 도착 · 통행료 " + toll + "만");
        }
    }

    private void beginTravel(P p) {
        travelOptions = new ArrayList<>();
        for (int i = 0; i < N; i++) if (i != p.pos) travelOptions.add(i);
        pendType = "TRAVEL"; pendTile = -1; step = Step.DECIDE;
        note(p.nick + " 세계여행 ✈️ · 이동할 칸 선택");
    }

    private void drawCard(P p) {
        Chance c = CARDS[ThreadLocalRandom.current().nextInt(CARDS.length)];
        // 우대권(다음 통행료 면제)만 비공개 — 상대가 모르게. 그 외(즉발·전체지급)는 로그에 내용 공개.
        boolean secret = isSecret(c);
        if (secret) note(p.nick + " 🔑 황금열쇠 카드를 뽑았다(비공개)");
        else note(p.nick + " 🔑 황금열쇠 · " + c.title + " — " + c.desc);
        applyCard(p, c);
        pendType = "CARD"; pendTile = -1; pendCard = c; pendCardOwner = p.seat; step = Step.DECIDE;
    }
    private static boolean isSecret(Chance c) { return "IMMUNITY".equals(c.kind()); }

    private void applyCard(P p, Chance c) {
        switch (c.kind()) {
            case "GAIN" -> p.cash += c.amount();
            case "PAY" -> { long a = Math.min(p.cash, c.amount()); p.cash -= a; pot += a; }
            case "TAX_PCT" -> { long a = p.cash * c.amount() / 100; p.cash -= a; pot += a; }
            case "IMMUNITY" -> p.tollImmunity = true;
            case "BIRTHDAY" -> { for (P o : players) if (o != p && o.alive) { long a = Math.min(o.cash, c.amount()); o.cash -= a; p.cash += a; } }
            case "TO_START" -> { p.pos = START_TILE; p.cash += SALARY; }
            case "TO_ISLAND" -> sendToIsland(p);
            default -> {}
        }
        checkBankrupt(p);
    }

    // ── 결정(구매/건설/통행료/인수/여행/카드확인) ──
    public synchronized void decide(String clientId, String action, int arg) {
        touch(); tick();
        requireTurn(clientId);
        if (step != Step.DECIDE) throw bad("결정할 것이 없습니다");
        P p = players.get(turnSeat);
        switch (pendType) {
            case "BUY" -> {
                if ("buy".equals(action)) doBuy(p, pendTile);
                else note(p.nick + " 구매 안 함");
                afterResolve(p);
            }
            case "UPGRADE" -> {
                if ("build".equals(action)) doBuild(p, pendTile);
                else note(p.nick + " 건설 안 함");
                afterResolve(p);
            }
            case "TOLL" -> {
                if ("takeover".equals(action) && canTakeover(p, pendTile)) doTakeover(p, pendTile);
                else doPayToll(p, pendTile);
                afterResolve(p);
            }
            case "TRAVEL" -> {
                int dest = arg;
                if (dest < 0 || dest >= N || dest == p.pos) dest = START_TILE;
                p.pos = dest; note(p.nick + " → " + BOARD[dest].name() + " 순간이동");
                clearPending();
                resolveLanding(p); // 도착 칸 효과 적용
            }
            case "OLYMPIC" -> {
                if (arg >= 0 && arg < N && owner[arg] == p.seat) { festivalTile = arg; note(p.nick + " " + BOARD[arg].name() + " 축제 개최! 통행료 2배 🏅"); }
                afterResolve(p);
            }
            case "CARD" -> { clearPending(); afterResolve(p); }
            default -> afterResolve(p);
        }
    }

    private void doBuy(P p, int i) {
        int price = BOARD[i].price();
        if (p.cash < price) { note(p.nick + " 현금 부족"); return; }
        p.cash -= price; owner[i] = p.seat; note(p.nick + " " + BOARD[i].name() + " 구매(-" + price + "만)");
    }
    private void doBuild(P p, int i) {
        int cost = buildCost(i);
        if (tier[i] >= 4 || p.cash < cost) return;
        p.cash -= cost; tier[i]++; note(p.nick + " " + BOARD[i].name() + " " + tierName(tier[i]) + " 건설(-" + cost + "만)");
    }
    private void doPayToll(P p, int i) {
        if (p.tollImmunity) { p.tollImmunity = false; note(p.nick + " 우대권으로 통행료 면제 🎟️"); return; }
        long toll = tollOf(i); P owr = players.get(owner[i]);
        long pay = Math.min(p.cash, toll);
        p.cash -= pay; owr.cash += pay;
        note(p.nick + " → " + owr.nick + " 통행료 " + pay + "만 지불");
        checkBankrupt(p);
    }
    private boolean canTakeover(P p, int i) {
        if (owner[i] == -1 || owner[i] == p.seat) return false;
        if (teamMode && players.get(owner[i]).team == p.team) return false;
        return p.cash >= takeoverCost(i);
    }
    private void doTakeover(P p, int i) {
        long cost = takeoverCost(i); P owr = players.get(owner[i]);
        p.cash -= cost; owr.cash += cost; owner[i] = p.seat;
        note(p.nick + " " + BOARD[i].name() + " 인수(" + cost + "만) → " + owr.nick + "에게 지불");
    }

    private void afterResolve(P p) {
        clearPending();
        step = Step.ROLL;
        if (!p.alive) { endTurn(); return; }
        if (phase == Phase.ENDED) return;
        if (extraRoll) { extraRoll = false; turnEndsAt = now() + TURN_MS; botAt = now() + BOT_DELAY_MS; step = Step.ROLL; return; }
        endTurn();
    }

    private void checkBankrupt(P p) {
        if (p.cash >= 0 || !p.alive) return;
        // 간단 파산: 잔액 음수면 탈락, 소유 도시 은행 반환
        p.alive = false; p.cash = 0;
        for (int i = 0; i < N; i++) if (owner[i] == p.seat) { owner[i] = -1; tier[i] = 0; }
        note("💥 " + p.nick + " 파산! 탈락");
        checkWin();
    }

    private void checkWin() {
        if (phase != Phase.PLAYING) return;
        // 생존 팀 수 계산(개인전은 팀=seat이라 각자 다른 팀)
        int aliveTeam = -1, teams = 0, last = -1;
        boolean[] seen = new boolean[players.size() + 1];
        for (P p : players) if (p.alive && !p.left) {
            last = p.seat;
            if (!seen[p.team]) { seen[p.team] = true; teams++; aliveTeam = p.team; }
        }
        if (teams <= 1) {
            phase = Phase.ENDED;
            winnerSeat = last;
            if (aliveTeam < 0) { winnerLabel = "무승부"; }
            else if (teamMode) {
                StringBuilder names = new StringBuilder();
                for (P p : players) if (p.team == aliveTeam) { if (names.length() > 0) names.append("·"); names.append(p.nick); }
                winnerLabel = names + " 팀 우승! 🏆";
            } else {
                winnerLabel = players.get(last).nick + " 우승! 🏆";
            }
            note("게임 종료 · " + winnerLabel);
        }
    }

    private void endTurn() {
        clearPending();
        extraRoll = false; step = Step.ROLL;
        if (phase != Phase.PLAYING) return;
        checkWin();
        if (phase != Phase.PLAYING) return;
        int guard = 0;
        do {
            turnSeat = (turnSeat + 1) % players.size();
            guard++;
        } while (guard <= players.size() * 2 && (!players.get(turnSeat).alive || players.get(turnSeat).left));
        beginTurn();
    }

    // ── 계산 ──
    private long tollOf(int i) {
        Tile t = BOARD[i];
        double base = t.price() * TOLL_MUL[tier[i]];
        long toll = Math.round(base);
        if (isMonopoly(owner[i], t.group())) toll *= 2;             // 독점 라인
        if (i == festivalTile) toll *= 2;                          // 축제
        return Math.max(1, toll);
    }
    private boolean isMonopoly(int seat, String group) {
        if (seat < 0 || group == null) return false;
        for (int i = 0; i < N; i++) if (group.equals(BOARD[i].group()) && owner[i] != seat) return false;
        return true;
    }
    private int buildCost(int i) { return (int) Math.round(BOARD[i].price() * BUILD_MUL[Math.min(3, tier[i])]); }
    private int takeoverCost(int i) { return (int) Math.round((BOARD[i].price() + tierInvested(i)) * 2.0); }
    private int tierInvested(int i) { int s = 0; for (int t = 0; t < tier[i]; t++) s += (int) Math.round(BOARD[i].price() * BUILD_MUL[t]); return s; }
    private static String tierName(int t) { return switch (t) { case 1 -> "별장"; case 2 -> "빌딩"; case 3 -> "호텔"; case 4 -> "랜드마크"; default -> "땅"; }; }

    // ── 봇/타임아웃 ──
    public synchronized void tick() {
        if (phase != Phase.PLAYING || turnSeat < 0) return;
        P cur = players.get(turnSeat);
        if (cur.left || !cur.alive) { endTurn(); return; }
        long t = now();
        if (cur.bot) { if (t >= botAt) botOneStep(cur); }
        else if (t >= turnEndsAt) autoStep(cur);
    }

    private void botOneStep(P p) {
        botAt = now() + BOT_DELAY_MS;
        if (step == Step.ROLL) { doRoll(p); return; }
        botDecide(p);
    }
    private void autoStep(P p) {
        if (step == Step.ROLL) doRoll(p);
        else botDecide(p); // 시간초과 시 자동 결정
    }

    private void botDecide(P p) {
        boolean greedy = !"EASY".equals(p.botLevel);
        switch (pendType) {
            case "BUY" -> decideInternal(p, greedy || ThreadLocalRandom.current().nextBoolean() ? "buy" : "pass", 0);
            case "UPGRADE" -> decideInternal(p, greedy || ThreadLocalRandom.current().nextBoolean() ? "build" : "skip", 0);
            case "TOLL" -> {
                boolean take = "HARD".equals(p.botLevel) && canTakeover(p, pendTile) && BOARD[pendTile].price() >= 200;
                decideInternal(p, take ? "takeover" : "pay", 0);
            }
            case "TRAVEL" -> decideInternal(p, "travel", botTravelPick(p));
            case "OLYMPIC" -> decideInternal(p, "olympic", botOlympicPick(p));
            case "CARD" -> decideInternal(p, "ack", 0);
            default -> afterResolve(p);
        }
    }
    private void decideInternal(P p, String action, int arg) {
        // decide() 내부 로직 재사용(락 재진입: 이미 synchronized 안)
        if (step != Step.DECIDE) return;
        switch (pendType) {
            case "BUY" -> { if ("buy".equals(action)) doBuy(p, pendTile); else note(p.nick + " 구매 안 함"); afterResolve(p); }
            case "UPGRADE" -> { if ("build".equals(action)) doBuild(p, pendTile); else note(p.nick + " 건설 안 함"); afterResolve(p); }
            case "TOLL" -> { if ("takeover".equals(action) && canTakeover(p, pendTile)) doTakeover(p, pendTile); else doPayToll(p, pendTile); afterResolve(p); }
            case "TRAVEL" -> { int d = (arg < 0 || arg >= N || arg == p.pos) ? START_TILE : arg; p.pos = d; note(p.nick + " → " + BOARD[d].name() + " 순간이동"); clearPending(); resolveLanding(p); }
            case "OLYMPIC" -> { if (arg >= 0 && arg < N && owner[arg] == p.seat) { festivalTile = arg; note(p.nick + " " + BOARD[arg].name() + " 축제 개최! 통행료 2배 🏅"); } afterResolve(p); }
            case "CARD" -> { clearPending(); afterResolve(p); }
            default -> afterResolve(p);
        }
    }
    private int botOlympicPick(P p) {
        int best = -1, bp = -1;
        for (int i = 0; i < N; i++) if (owner[i] == p.seat && BOARD[i].price() > bp) { bp = BOARD[i].price(); best = i; }
        return best;
    }
    private int botTravelPick(P p) {
        // 빈 도시 중 가장 비싼 곳(사서 라인 노림), 없으면 출발
        int best = -1, bp = -1;
        for (int i = 0; i < N; i++) if ("CITY".equals(BOARD[i].type()) && owner[i] == -1 && BOARD[i].price() <= p.cash && BOARD[i].price() > bp) { bp = BOARD[i].price(); best = i; }
        return best >= 0 ? best : START_TILE;
    }

    // ── 상태 뷰 ──
    public synchronized MonopolyState me(String clientId) {
        touch(); tick();
        P me = byClient(clientId);
        int meSeat = me == null ? -1 : me.seat;

        List<PlayerView> pv = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            P p = players.get(i);
            if (p.left && phase == Phase.LOBBY) continue;
            int props = 0; for (int k = 0; k < N; k++) if (owner[k] == p.seat) props++;
            pv.add(new PlayerView(p.seat, p.nick, p.bot, p.host, p == me, p.color, p.team, p.cash, p.pos, p.alive, p.inIsland, p.left, props));
        }
        List<TileView> tv = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            Tile t = BOARD[i];
            tv.add(new TileView(i, t.name(), t.type(), t.group(), t.price(), owner[i], tier[i], i == festivalTile));
        }
        boolean myTurn = phase == Phase.PLAYING && meSeat == turnSeat && me != null && me.alive && !me.left;
        String turnName = phase == Phase.PLAYING && turnSeat >= 0 ? players.get(turnSeat).nick : null;

        Pending pend = null;
        if (phase == Phase.PLAYING && step == Step.DECIDE && !"NONE".equals(pendType)) {
            long toll = "TOLL".equals(pendType) ? tollOf(pendTile) : 0;
            int buyPrice = "BUY".equals(pendType) ? BOARD[pendTile].price() : 0;
            int upCost = "UPGRADE".equals(pendType) ? buildCost(pendTile) : 0;
            int takeCost = "TOLL".equals(pendType) ? takeoverCost(pendTile) : 0;
            boolean canBuild = "UPGRADE".equals(pendType) && tier[pendTile] < 4;
            // 카드 내용은 소유자에게만
            Card cardView = null;
            if ("CARD".equals(pendType) && pendCard != null && meSeat == pendCardOwner)
                cardView = new Card(pendCard.icon(), pendCard.title(), pendCard.desc());
            List<Integer> topts = ("TRAVEL".equals(pendType) || "OLYMPIC".equals(pendType)) ? travelOptions : null;
            pend = new Pending(pendType, pendTile, toll, buyPrice, upCost, takeCost, canBuild, topts, cardView);
        }

        return new MonopolyState(
                phase.name(),
                teamMode,
                clientId != null && clientId.equals(hostClientId),
                me != null,
                pv, tv, turnSeat, turnName, myTurn, meSeat,
                dice, lastDouble, step.name(), pend, pot,
                lastAction, new ArrayList<>(log), winnerSeat, winnerLabel,
                turnEndsAt, now());
    }

    // ── RoomGame ──
    @Override public String roomStatus() { return switch (phase) { case LOBBY -> "WAITING"; case ENDED -> "ENDED"; default -> "PLAYING"; }; }
    @Override public int playerCount() { int n = 0; for (P p : players) if (!p.bot && !p.left) n++; return n; }
    @Override public String hostLabel() { for (P p : players) if (p.host) return p.nick; return "-"; }
    @Override public boolean isEnded() { return phase == Phase.ENDED; }
    @Override public long lastActiveMs() { return lastActive; }
    @Override public synchronized void leave(String clientId) {
        P p = byClient(clientId); if (p == null) return;
        if (phase == Phase.LOBBY) players.remove(p);
        else { p.left = true; if (phase == Phase.PLAYING && p.seat == turnSeat) endTurn(); else checkWin(); }
        touch();
    }

    // ── 유틸 ──
    public synchronized Phase phase() { return phase; }
    public int turnSeat() { return turnSeat; }
    public int winnerSeat() { return winnerSeat; }
    public P byClient(String clientId) { if (clientId == null) return null; for (P p : players) if (clientId.equals(p.clientId)) return p; return null; }
    List<P> playersList() { return players; }
    int[] ownerArr() { return owner; }
    int[] tierArr() { return tier; }
    long potAmount() { return pot; }

    private void requireTurn(String clientId) {
        if (phase != Phase.PLAYING) throw bad("게임 중이 아닙니다");
        P p = byClient(clientId);
        if (p == null || p.seat != turnSeat) throw bad("당신 차례가 아닙니다");
        if (!p.alive) throw bad("이미 탈락했습니다");
    }
    private int activeCount() { int n = 0; for (P p : players) if (!p.left) n++; return n; }
    private void requireHost(String c) { if (!hostClientId.equals(c)) throw bad("방장만 할 수 있습니다"); }
    private String botName() { int n = 1; for (P p : players) if (p.bot) n++; return "봇" + n; }
    private void touch() { lastActive = now(); }
    private static long now() { return System.currentTimeMillis(); }
    private static String clean(String s) { String n = s == null ? "" : s.trim(); if (n.isEmpty()) n = "익명"; return n.length() > 16 ? n.substring(0, 16) : n; }
    private static String normLevel(String s) { String u = s == null ? "NORMAL" : s.toUpperCase(); return switch (u) { case "EASY", "HARD", "NORMAL" -> u; default -> "NORMAL"; }; }
    private static BusinessException bad(String m) { return new BusinessException(ErrorCode.INVALID_INPUT, m); }
}
