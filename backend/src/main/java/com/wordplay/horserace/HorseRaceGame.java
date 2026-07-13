package com.wordplay.horserace;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomGame;
import com.wordplay.horserace.dto.HorseRaceStateResponse;
import com.wordplay.horserace.dto.HorseRaceStateResponse.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 경마(Horse Race) 게임 — v1: 기본경마 · 고정배당 · 단승/연승.
 * 세션 순환 로스터(직전 top3 재출전), 선(先)계산 타임라인 동기재생, 봇 배팅자.
 * 인메모리·폴링. 계정 잔고 반영은 {@link #pollAccountSettlements()}로 컨트롤러가 flush.
 */
public class HorseRaceGame implements RoomGame {

    enum Phase { LOBBY, BETTING, RACING, RESULT, ENDED }
    enum Style { FRONT, CLOSER, EVEN }

    // ---- 튜닝 상수(§5.4) ----
    private static final int FINISH = 1500;         // 결승 거리
    private static final int BASE0 = 40;            // 기본 전진
    private static final double CONDSTEP = 1.5;     // 컨디션 ★ 반영
    private static final double FORMSTEP = 1.8;     // 연승 폼 보너스(캡 3)
    private static final double NOISE = 30;         // 매 tick 노이즈(크게 → 언더독)
    private static final double PACE = 0.15;        // 각질 곡선 세기
    private static final int MAXT = 120;            // tick 상한
    private static final int TICK_MS = 240;
    private static final double MARGIN = 0.20;      // 하우스 마진
    private static final int ODDS_SIMS = 2500;      // 배당 산출 몬테카를로 횟수
    private static final int CARRY = 3;             // 이월 두수(top3)
    private static final int BET_UNIT = 10;
    private static final int MAX_BOTS = 5;
    private static final double SEED = 10_000;      // 펀드풀 하우스 시드(유동성) — 소인원 배당 보정

    static final class Player {
        final String clientId; String nick;
        boolean bot = false; boolean account = false; Long accountId = null;
        long chips; long chipsBeforeRace;
        Player(String clientId, String nick) { this.clientId = clientId; this.nick = nick; }
    }

    static final class Horse {
        int id; String name; String emoji;
        int condition; Style style;
        final List<Integer> formLine = new ArrayList<>();
        int streak; boolean isNew = true;
        Horse(int id, String name, String emoji) { this.id = id; this.name = name; this.emoji = emoji; }
    }

    static final class Bet { int seat; String type; int[] picks; long amount; }

    public record AccountSettle(long accountId, long balance, boolean raced, boolean won) {}

    private static final String[] NAMES = {
            "천둥번개", "질풍", "은하수", "폭주기관차", "다크호스", "무지개", "황금발굽", "돌풍",
            "번개탄", "슈퍼노바", "칠전팔기", "바람돌이", "명마루", "불꽃", "청춘", "대박이",
            "느림보", "미친듯이", "구름위로", "홈런왕" };
    private static final String[] EMOJIS = { "🐎", "🦄", "🐴", "🏇" };

    private Phase phase = null;
    private long lastActiveMs = System.currentTimeMillis();
    private String hostClientId = null;
    private final List<Player> players = new ArrayList<>();
    private final Map<String, Integer> seats = new HashMap<>();
    private final Set<String> leftClients = new HashSet<>();

    // 방 설정
    private String raceType = "BASIC";
    private String oddsMode = "FIXED";
    private int buyIn = 5_000;
    private int betSec = 25;
    private int horseCount = 9;
    private int autoEndRounds = 0; // 0 = 무한
    private int round = 0;

    // 진행 상태
    private final List<Horse> horses = new ArrayList<>();
    private double[] oddsWin = new double[0];
    private double[] oddsPlace = new double[0];
    private Map<String, Double> oddsExacta = new HashMap<>();
    private Map<String, Double> oddsTrio = new HashMap<>();
    // 펀드풀(PARIMUTUEL) 판돈 풀(하우스 시드 + 실제 배팅)
    private double[] poolWin = new double[0];
    private double[] poolPlace = new double[0];
    private Map<String, Double> poolExacta = new HashMap<>();
    private Map<String, Double> poolTrio = new HashMap<>();
    private final List<Bet> bets = new ArrayList<>();
    private long betEndsAt = 0;
    private int[][] timeline = null;     // [T][N]
    private int[] finishOrder = new int[0];
    private long raceStartAt = 0;
    private long raceEndsAt = 0;
    private int botCounter = 0;
    private int horseIdCounter = 0;
    private long version = 0;

    private final List<AccountSettle> pendingSettles = new ArrayList<>();

    // =================== 명령 ===================

    public synchronized HorseRaceStateResponse newGame(String clientId, String nick, Long accountId, long accountBalance,
                                                       String raceType, String oddsMode, Integer buyIn,
                                                       Integer betSec, Integer horseCount, Integer autoEndRounds) {
        reset();
        phase = Phase.LOBBY;
        hostClientId = clientId;
        this.raceType = "SPECIAL".equalsIgnoreCase(raceType) ? "SPECIAL" : "BASIC";
        this.oddsMode = "PARIMUTUEL".equalsIgnoreCase(oddsMode) ? "PARIMUTUEL" : "FIXED";
        this.buyIn = clamp(buyIn == null ? 5_000 : buyIn, 500, 1_000_000);
        this.betSec = clamp(betSec == null ? 25 : betSec, 10, 120);
        this.horseCount = clamp(horseCount == null ? 9 : horseCount, 4, 12);
        this.autoEndRounds = autoEndRounds == null ? 0 : Math.max(0, autoEndRounds);
        addPlayer(clientId, nick, accountId, accountBalance);
        touch();
        return me(clientId);
    }

    public synchronized HorseRaceStateResponse join(String clientId, String nick, Long accountId, long accountBalance) {
        if (phase == null) throw bad("생성된 방이 없습니다");
        if (!seats.containsKey(clientId)) {
            addPlayer(clientId, nick, accountId, accountBalance);
        } else {
            players.get(seats.get(clientId)).nick = trimNick(nick);
        }
        touch();
        return me(clientId);
    }

    public synchronized HorseRaceStateResponse addBot(String clientId) {
        if (!clientId.equals(hostClientId)) throw bad("방장만 봇을 추가할 수 있습니다");
        long bots = players.stream().filter(p -> p.bot).count();
        if (bots >= MAX_BOTS) throw bad("봇은 최대 " + MAX_BOTS + "명입니다");
        botCounter++;
        Player b = new Player("bot::" + System.nanoTime(), "🤖 봇" + botCounter);
        b.bot = true; b.chips = buyIn;
        players.add(b);
        seats.put(b.clientId, players.size() - 1);
        touch();
        return me(clientId);
    }

    public synchronized HorseRaceStateResponse start(String clientId) {
        if (phase != Phase.LOBBY) throw bad("지금 시작할 수 없습니다");
        if (!clientId.equals(hostClientId)) throw bad("방장만 시작할 수 있습니다");
        if (activePlayerCount() < 1) throw bad("참가자가 필요합니다");
        beginBetting(null);
        return me(clientId);
    }

    public synchronized HorseRaceStateResponse bet(String clientId, String type, int[] picks, long amount) {
        tick();
        Player p = requirePlayer(clientId);
        if (phase != Phase.BETTING) throw bad("지금은 배팅할 수 없습니다");
        String t = normalizeBetType(type);
        int need = pickCount(t);
        if (picks == null || picks.length != need) throw bad("말 " + need + "마리를 선택하세요");
        Set<Integer> uniq = new HashSet<>();
        for (int h : picks) {
            if (h < 0 || h >= horses.size()) throw bad("말 선택이 올바르지 않습니다");
            if (!uniq.add(h)) throw bad("같은 말을 중복 선택했습니다");
        }
        if (amount < BET_UNIT || amount % BET_UNIT != 0) throw bad(BET_UNIT + "칩 단위로 배팅하세요");
        if (amount > p.chips) throw bad("보유 칩이 부족합니다");
        p.chips -= amount;
        Bet b = new Bet(); b.seat = seats.get(clientId); b.type = t; b.picks = picks.clone(); b.amount = amount;
        bets.add(b);
        if ("PARIMUTUEL".equals(oddsMode)) addToPool(t, picks, amount);
        touch();
        return me(clientId);
    }

    private static String normalizeBetType(String type) {
        if (type == null) return "WIN";
        return switch (type.toUpperCase()) {
            case "PLACE" -> "PLACE";
            case "EXACTA" -> "EXACTA";
            case "TRIO" -> "TRIO";
            default -> "WIN";
        };
    }
    private static int pickCount(String t) {
        return switch (t) { case "EXACTA" -> 2; case "TRIO" -> 3; default -> 1; };
    }

    public synchronized HorseRaceStateResponse nextRace(String clientId) {
        if (!clientId.equals(hostClientId)) throw bad("방장만 다음 레이스를 시작할 수 있습니다");
        if (phase != Phase.RESULT) throw bad("지금은 다음 레이스를 시작할 수 없습니다");
        if (autoEndRounds > 0 && round >= autoEndRounds) { phase = Phase.ENDED; touch(); return me(clientId); }
        beginBetting(finishOrder);
        return me(clientId);
    }

    public synchronized HorseRaceStateResponse endGame(String clientId) {
        if (!clientId.equals(hostClientId)) throw bad("방장만 종료할 수 있습니다");
        phase = Phase.ENDED;
        touch();
        return me(clientId);
    }

    /** 재기 보너스 등으로 계정 잔고가 바뀌었을 때 방 내 칩 동기화. */
    public synchronized void syncAccountChips(long accountId, long newBalance) {
        for (Player p : players) if (p.account && p.accountId != null && p.accountId == accountId) {
            if (phase == Phase.BETTING || phase == Phase.RESULT || phase == Phase.LOBBY) p.chips = newBalance;
        }
        touch();
    }

    public synchronized List<AccountSettle> pollAccountSettlements() {
        if (pendingSettles.isEmpty()) return List.of();
        List<AccountSettle> out = new ArrayList<>(pendingSettles);
        pendingSettles.clear();
        return out;
    }

    public synchronized HorseRaceStateResponse me(String clientId) {
        lastActiveMs = System.currentTimeMillis();
        tick();
        return build(clientId);
    }

    // =================== 진행 ===================

    private void beginBetting(int[] prevOrder) {
        round++;
        buildRoster(prevOrder);
        computeOdds();
        bets.clear();
        timeline = null; finishOrder = new int[0]; raceStartAt = 0; raceEndsAt = 0;
        for (Player p : players) p.chipsBeforeRace = p.chips;
        placeBotBets();
        phase = Phase.BETTING;
        betEndsAt = System.currentTimeMillis() + betSec * 1000L;
        touch();
    }

    private void tick() {
        if (phase == Phase.BETTING && betEndsAt > 0 && System.currentTimeMillis() >= betEndsAt) {
            runRace();
        }
        if (phase == Phase.RACING && raceEndsAt > 0 && System.currentTimeMillis() >= raceEndsAt) {
            settle();
        }
    }

    private void runRace() {
        Horse[] hs = horses.toArray(new Horse[0]);
        SimResult r = simulate(hs, true, new Random(ThreadLocalRandom.current().nextLong()));
        timeline = r.timeline;
        finishOrder = r.order;
        raceStartAt = System.currentTimeMillis();
        raceEndsAt = raceStartAt + (long) timeline.length * TICK_MS + 1200; // +포토피니시 여유
        phase = Phase.RACING;
        touch();
    }

    private void settle() {
        if ("PARIMUTUEL".equals(oddsMode)) recomputeParimutuelOdds(); // 마감 시점 최종 배당 확정
        Set<Integer> top3 = new HashSet<>();
        for (int i = 0; i < Math.min(3, finishOrder.length); i++) top3.add(finishOrder[i]);
        int winner = finishOrder.length > 0 ? finishOrder[0] : -1;
        int second = finishOrder.length > 1 ? finishOrder[1] : -1;

        for (Bet b : bets) {
            boolean hit;
            double odds;
            switch (b.type) {
                case "PLACE" -> { hit = top3.contains(b.picks[0]); odds = oddsPlace[b.picks[0]]; }
                case "EXACTA" -> { hit = b.picks[0] == winner && b.picks[1] == second; odds = oddsExacta.getOrDefault(exactaKey(b.picks[0], b.picks[1]), 50.0); }
                case "TRIO" -> { hit = top3.contains(b.picks[0]) && top3.contains(b.picks[1]) && top3.contains(b.picks[2]); odds = oddsTrio.getOrDefault(trioKey(b.picks[0], b.picks[1], b.picks[2]), 50.0); }
                default -> { hit = b.picks[0] == winner; odds = oddsWin[b.picks[0]]; }
            }
            if (!hit) continue;
            players.get(b.seat).chips += Math.round(b.amount * odds);
        }

        // 말 폼/스트릭 갱신
        for (int rank = 0; rank < finishOrder.length; rank++) {
            Horse h = horses.get(finishOrder[rank]);
            h.formLine.add(rank + 1);
            if (h.formLine.size() > 5) h.formLine.remove(0);
            h.streak = rank < 3 ? h.streak + 1 : 0;
        }

        // 계정 정산 큐
        pendingSettles.clear();
        for (Player p : players) {
            if (p.account && p.accountId != null && !leftClients.contains(p.clientId)) {
                boolean won = p.chips > p.chipsBeforeRace;
                pendingSettles.add(new AccountSettle(p.accountId, p.chips, true, won));
            }
        }
        phase = Phase.RESULT;
        touch();
    }

    // =================== 로스터 / 배당 ===================

    private void buildRoster(int[] prevOrder) {
        List<Horse> carry = new ArrayList<>();
        if (prevOrder != null && prevOrder.length > 0) {
            for (int i = 0; i < Math.min(CARRY, prevOrder.length); i++) carry.add(horses.get(prevOrder[i]));
        }
        horses.clear();
        for (Horse h : carry) { h.isNew = false; h.condition = rollCondition(); h.style = rollStyle(); horses.add(h); }
        while (horses.size() < horseCount) {
            Horse h = new Horse(++horseIdCounter, NAMES[horseIdCounter % NAMES.length],
                    EMOJIS[ThreadLocalRandom.current().nextInt(EMOJIS.length)]);
            h.condition = rollCondition(); h.style = rollStyle(); h.isNew = true;
            horses.add(h);
        }
        java.util.Collections.shuffle(horses, new Random(ThreadLocalRandom.current().nextLong()));
    }

    private void computeOdds() {
        int n = horses.size();
        Horse[] hs = horses.toArray(new Horse[0]);
        int[] win = new int[n], place = new int[n];
        Map<String, Integer> exacta = new HashMap<>(), trio = new HashMap<>();
        Random rng = new Random(ThreadLocalRandom.current().nextLong());
        for (int s = 0; s < ODDS_SIMS; s++) {
            int[] order = simulate(hs, false, rng).order;
            win[order[0]]++;
            for (int i = 0; i < Math.min(3, order.length); i++) place[order[i]]++;
            if (order.length >= 2) exacta.merge(exactaKey(order[0], order[1]), 1, Integer::sum);
            if (order.length >= 3) trio.merge(trioKey(order[0], order[1], order[2]), 1, Integer::sum);
        }
        double sims = ODDS_SIMS;
        if ("PARIMUTUEL".equals(oddsMode)) {
            // 확률 비례로 하우스 시드를 깔면 초기 배당 = 고정배당과 동일, 이후 실제 배팅으로 변동
            poolWin = new double[n]; poolPlace = new double[n];
            for (int i = 0; i < n; i++) {
                poolWin[i] = Math.max(1.0, SEED * win[i] / sims);
                poolPlace[i] = Math.max(1.0, SEED * place[i] / sims);
            }
            poolExacta = new HashMap<>(); poolTrio = new HashMap<>();
            exacta.forEach((k, c) -> poolExacta.put(k, SEED * c / sims));
            trio.forEach((k, c) -> poolTrio.put(k, SEED * c / sims));
            recomputeParimutuelOdds();
        } else {
            oddsWin = new double[n]; oddsPlace = new double[n];
            for (int i = 0; i < n; i++) {
                oddsWin[i] = oddsFrom(win[i] / sims);
                oddsPlace[i] = oddsFrom(place[i] / sims);
            }
            oddsExacta = new HashMap<>(); oddsTrio = new HashMap<>();
            exacta.forEach((k, c) -> oddsExacta.put(k, oddsFrom(c / sims)));
            trio.forEach((k, c) -> oddsTrio.put(k, oddsFrom(c / sims)));
        }
    }

    /** 펀드풀 현재 풀에서 배당 재계산: 배당 = 풀총액×(1−마진) / 해당풀. */
    private void recomputeParimutuelOdds() {
        int n = poolWin.length;
        double totW = sum(poolWin), totP = sum(poolPlace);
        oddsWin = new double[n]; oddsPlace = new double[n];
        for (int i = 0; i < n; i++) {
            oddsWin[i] = clampOdds(totW * (1 - MARGIN) / poolWin[i]);
            oddsPlace[i] = clampOdds(totP * (1 - MARGIN) / poolPlace[i]);
        }
        double totE = poolExacta.values().stream().mapToDouble(Double::doubleValue).sum();
        double totT = poolTrio.values().stream().mapToDouble(Double::doubleValue).sum();
        oddsExacta = new HashMap<>(); oddsTrio = new HashMap<>();
        poolExacta.forEach((k, v) -> oddsExacta.put(k, clampOdds(totE * (1 - MARGIN) / v)));
        poolTrio.forEach((k, v) -> oddsTrio.put(k, clampOdds(totT * (1 - MARGIN) / v)));
    }

    private void addToPool(String type, int[] picks, long amount) {
        switch (type) {
            case "PLACE" -> poolPlace[picks[0]] += amount;
            case "EXACTA" -> poolExacta.merge(exactaKey(picks[0], picks[1]), (double) amount, Double::sum);
            case "TRIO" -> poolTrio.merge(trioKey(picks[0], picks[1], picks[2]), (double) amount, Double::sum);
            default -> poolWin[picks[0]] += amount;
        }
    }

    private static double sum(double[] a) { double s = 0; for (double v : a) s += v; return s; }
    private static double clampOdds(double o) {
        o = Math.max(1.1, Math.min(50.0, o));
        return Math.round(o * 10) / 10.0;
    }

    private static String exactaKey(int a, int b) { return a + "-" + b; }
    private static String trioKey(int a, int b, int c) {
        int[] s = { a, b, c }; Arrays.sort(s);
        return s[0] + "-" + s[1] + "-" + s[2];
    }

    private static double oddsFrom(double p) {
        if (p <= 0) return 50.0;
        double o = (1.0 / p) * (1 - MARGIN);
        o = Math.max(1.1, Math.min(50.0, o));
        return Math.round(o * 10) / 10.0;
    }

    // =================== 레이스 시뮬 ===================

    private record SimResult(int[] order, int[][] timeline) {}

    private static SimResult simulate(Horse[] hs, boolean record, Random rng) {
        int n = hs.length;
        double[] pos = new double[n];
        int[] finishTick = new int[n]; Arrays.fill(finishTick, -1);
        double[] over = new double[n];
        List<int[]> frames = record ? new ArrayList<>() : null;
        int finished = 0, t = 0;
        while (t < MAXT && finished < n) {
            for (int i = 0; i < n; i++) {
                if (finishTick[i] >= 0) continue;
                double prog = Math.min(1.0, pos[i] / FINISH);
                double base = BASE0 + hs[i].condition * CONDSTEP + Math.min(hs[i].streak, 3) * FORMSTEP;
                double step = base * paceMult(hs[i].style, prog) + (rng.nextDouble() * 2 - 1) * NOISE;
                if (step < 0) step = 0;
                pos[i] += step;
                if (pos[i] >= FINISH) { finishTick[i] = t; over[i] = pos[i]; finished++; }
            }
            if (record) {
                int[] fr = new int[n];
                for (int i = 0; i < n; i++) fr[i] = (int) Math.min(pos[i], FINISH);
                frames.add(fr);
            }
            t++;
        }
        for (int i = 0; i < n; i++) if (finishTick[i] < 0) { finishTick[i] = MAXT; over[i] = pos[i]; }
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        final int[] ft = finishTick; final double[] ov = over;
        final double[] tie = new double[n];
        for (int i = 0; i < n; i++) tie[i] = rng.nextDouble();
        Arrays.sort(idx, (a, b) -> {
            if (ft[a] != ft[b]) return Integer.compare(ft[a], ft[b]);
            if (ov[a] != ov[b]) return Double.compare(ov[b], ov[a]);
            return Double.compare(tie[a], tie[b]);
        });
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = idx[i];
        return new SimResult(order, record ? frames.toArray(new int[0][]) : null);
    }

    private static double paceMult(Style s, double prog) {
        return switch (s) {
            case FRONT -> 1.0 + PACE * (1 - 2 * prog);   // 초반↑ 후반↓
            case CLOSER -> 1.0 + PACE * (2 * prog - 1);  // 초반↓ 후반↑
            default -> 1.0;
        };
    }

    // =================== 봇 배팅 ===================

    private void placeBotBets() {
        for (Player p : players) {
            if (!p.bot || p.chips < BET_UNIT) continue;
            int n = horses.size();
            // 성향: 인기마(낮은 배당) or 언더독(높은 배당)
            boolean underdog = ThreadLocalRandom.current().nextInt(3) == 0;
            int pick = 0; double best = underdog ? -1 : Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double o = oddsWin[i];
                if (underdog ? o > best : o < best) { best = o; pick = i; }
            }
            String type = ThreadLocalRandom.current().nextInt(2) == 0 ? "WIN" : "PLACE";
            long amt = Math.max(BET_UNIT, (p.chips / 5) / BET_UNIT * BET_UNIT);
            amt = Math.min(amt, p.chips);
            if (amt < BET_UNIT) continue;
            p.chips -= amt;
            int[] pk = new int[]{ pick };
            Bet b = new Bet(); b.seat = indexOf(p); b.type = type; b.picks = pk; b.amount = amt;
            bets.add(b);
            if ("PARIMUTUEL".equals(oddsMode)) addToPool(type, pk, amt);
        }
    }

    private int indexOf(Player p) {
        for (int i = 0; i < players.size(); i++) if (players.get(i) == p) return i;
        return -1;
    }

    // =================== 응답 ===================

    private HorseRaceStateResponse build(String clientId) {
        long now = System.currentTimeMillis();
        if (phase == null) return HorseRaceStateResponse.notStarted(now);
        if ("PARIMUTUEL".equals(oddsMode) && phase == Phase.BETTING && poolWin.length > 0) recomputeParimutuelOdds();
        Integer mySeat = seats.get(clientId);
        boolean joined = mySeat != null;
        Player meP = joined ? players.get(mySeat) : null;
        long totalPool = 0; for (Bet b : bets) totalPool += b.amount;

        List<HorseView> hv = new ArrayList<>();
        for (int i = 0; i < horses.size(); i++) {
            Horse h = horses.get(i);
            hv.add(new HorseView(i, h.name, h.emoji, h.condition, h.style.name(),
                    new ArrayList<>(h.formLine), h.streak, h.isNew,
                    i < oddsWin.length ? oddsWin[i] : 0, i < oddsPlace.length ? oddsPlace[i] : 0, i + 1));
        }
        List<PlayerView> pv = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            if (leftClients.contains(p.clientId)) continue;
            pv.add(new PlayerView(i + 1, p.nick, p.chips, p.bot, p.account, p.clientId.equals(hostClientId)));
        }
        List<BetView> mine = new ArrayList<>();
        if (joined) for (Bet b : bets) if (b.seat == mySeat) mine.add(new BetView(b.type, toList(b.picks), b.amount));

        RaceView race = null;
        if ((phase == Phase.RACING || phase == Phase.RESULT) && timeline != null) {
            List<List<Integer>> tl = new ArrayList<>();
            for (int[] fr : timeline) { List<Integer> row = new ArrayList<>(); for (int v : fr) row.add(v); tl.add(row); }
            race = new RaceView(tl, toList(finishOrder), raceStartAt, TICK_MS, FINISH, List.of());
        }
        long myNet = meP != null && phase == Phase.RESULT ? meP.chips - meP.chipsBeforeRace : 0;
        boolean canBonus = meP != null && meP.account && meP.chips < com.wordplay.horserace.account.RaceAccountService.BONUS_THRESHOLD;
        boolean betting = phase == Phase.BETTING;

        return new HorseRaceStateResponse(
                phase.name(), now, raceType, oddsMode, round,
                clientId.equals(hostClientId), joined, joined ? mySeat + 1 : 0,
                meP != null ? meP.nick : null, meP != null && meP.account, meP != null ? meP.chips : 0, canBonus,
                betEndsAt, betSec, hv, pv, mine, race,
                betting ? oddsExacta : Map.of(), betting ? oddsTrio : Map.of(),
                phase == Phase.RESULT ? toList(finishOrder) : List.of(), myNet,
                buyIn, horseCount, activePlayerCount(), totalPool, version);
    }

    private static List<Integer> toList(int[] a) {
        List<Integer> l = new ArrayList<>(a.length);
        for (int v : a) l.add(v);
        return l;
    }

    // =================== RoomGame ===================

    @Override public synchronized String roomStatus() {
        if (phase == null || phase == Phase.LOBBY) return "WAITING";
        return phase == Phase.ENDED ? "ENDED" : "PLAYING";
    }
    @Override public synchronized int playerCount() { return activePlayerCount(); }
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

    /** 방에 남아있는 계정들의 accountId 목록(leave 시 방 점유 해제용). */
    public synchronized List<Long> accountIdsInRoom() {
        List<Long> ids = new ArrayList<>();
        for (Player p : players) if (p.account && p.accountId != null) ids.add(p.accountId);
        return ids;
    }
    public synchronized Long accountIdOfClient(String clientId) {
        Integer s = seats.get(clientId);
        return s == null ? null : players.get(s).accountId;
    }

    // 테스트용
    static double favWinRateForTest(int n, int favCondition, int otherCondition, int sims) {
        Horse[] hs = new Horse[n];
        for (int i = 0; i < n; i++) {
            hs[i] = new Horse(i, "h" + i, "🐎");
            hs[i].condition = i == 0 ? favCondition : otherCondition;
            hs[i].style = Style.EVEN;
        }
        Random rng = new Random(42);
        int win = 0;
        for (int s = 0; s < sims; s++) if (simulate(hs, false, rng).order[0] == 0) win++;
        return win / (double) sims;
    }
    List<Horse> horsesForTest() { return horses; }
    double[] oddsWinForTest() { return oddsWin; }
    int[] finishOrderForTest() { return finishOrder; }
    Phase phaseForTest() { return phase; }
    void forceBetEndForTest() { betEndsAt = 1; }
    void forceRaceEndForTest() { raceEndsAt = 1; }

    // =================== 유틸 ===================

    private int activePlayerCount() {
        return (int) players.stream().filter(p -> !p.bot && !leftClients.contains(p.clientId)).count();
    }
    private void addPlayer(String clientId, String nick, Long accountId, long accountBalance) {
        Player p = new Player(clientId, trimNick(nick));
        if (accountId != null) { p.account = true; p.accountId = accountId; p.chips = accountBalance; }
        else { p.chips = buyIn; }
        seats.put(clientId, players.size());
        players.add(p);
    }
    private Player requirePlayer(String clientId) {
        Integer s = seats.get(clientId);
        if (s == null) throw bad("참가하지 않은 기기입니다");
        return players.get(s);
    }
    private static int rollCondition() { return 1 + ThreadLocalRandom.current().nextInt(5); }
    private static Style rollStyle() { return Style.values()[ThreadLocalRandom.current().nextInt(3)]; }
    private void reset() {
        phase = null; hostClientId = null;
        players.clear(); seats.clear(); leftClients.clear(); horses.clear(); bets.clear();
        pendingSettles.clear();
        oddsWin = new double[0]; oddsPlace = new double[0]; oddsExacta = new HashMap<>(); oddsTrio = new HashMap<>();
        poolWin = new double[0]; poolPlace = new double[0]; poolExacta = new HashMap<>(); poolTrio = new HashMap<>();
        raceType = "BASIC"; oddsMode = "FIXED"; buyIn = 5_000; betSec = 25; horseCount = 9; autoEndRounds = 0;
        round = 0; betEndsAt = 0; timeline = null; finishOrder = new int[0];
        raceStartAt = 0; raceEndsAt = 0; botCounter = 0; horseIdCounter = 0; version = 0;
    }
    private void touch() { version++; lastActiveMs = System.currentTimeMillis(); }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static BusinessException bad(String msg) { return new BusinessException(ErrorCode.INVALID_INPUT, msg); }
    private static String trimNick(String nick) {
        String t = nick == null ? "" : nick.trim();
        if (t.isEmpty()) t = "익명";
        return t.length() > 16 ? t.substring(0, 16) : t;
    }
}
