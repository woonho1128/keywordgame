package com.wordplay.quiz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordplay.mafia.ai.OpenAiChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 상식 퀴즈 문제 창고. AI가 만든 문제를 모아두고 방에 나눠준다.
 *
 * <p>비용 설계가 핵심이다. 문제 하나마다 호출하면 판마다 수십 번을 부르게 되므로:
 * <ul>
 *   <li>한 번에 {@value #BATCH}문제를 묶어 받는다(호출 수 1/{@value #BATCH}).</li>
 *   <li>난이도별 창고에 쌓아두고 방끼리 공유한다 — 같은 문제를 두 번 만들지 않는다.</li>
 *   <li>이미 낸 문제는 지문 기준으로 걸러 중복 출제를 막는다.</li>
 *   <li>하루 호출 상한을 두고, 넘으면 내장 문제로 돌린다(게임은 계속 된다).</li>
 * </ul>
 *
 * <p>system 프롬프트는 고정이라 OpenAI 프롬프트 캐싱이 걸린다. 사용량은 마피아와 같은
 * 트래커에 {@code 퀴즈} 종류로 기록되어 {@code /api/v1/mafia/ai-usage}에서 함께 보인다.
 */
@Slf4j
@Component
public class QuizBank {

    /** 한 번 호출에 받아오는 문제 수. 늘리면 호출은 줄지만 응답이 길어져 실패 위험이 커진다. */
    static final int BATCH = 10;
    /** 난이도별로 이만큼 남으면 더 만들어 둔다. */
    static final int LOW_WATER = 4;
    /** 창고가 무한히 커지지 않게 난이도별 상한. */
    static final int POOL_MAX = 60;
    /** 중복 판정용 지문 기억 개수. */
    static final int SEEN_MAX = 4000;

    private final OpenAiChatClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 난이도(1~10) -> 대기 중인 문제들.
     *
     * <p>락 없이 꺼낼 수 있어야 한다. 무제한 모드는 진행 중에 문제를 보충하는데, 그때
     * 배경 생성이 잡은 락을 기다리면 게임이 몇 초 멈춘다.
     */
    private final Map<Integer, Deque<QuizQuestion>> pool = new ConcurrentHashMap<>();
    /** 난이도별 생성이 이미 돌고 있는지. 같은 난이도로 동시에 여러 번 부르지 않는다. */
    private final Map<Integer, AtomicBoolean> generating = new ConcurrentHashMap<>();
    /** 이미 만든 지문(정규화) — 중복 출제 방지. */
    private final Set<String> seen = Collections.synchronizedSet(new LinkedHashSet<>());

    /** 하루 호출 상한. 넘으면 내장 문제만 쓴다. */
    @Value("${app.quiz.daily-call-limit:200}")
    private int dailyCallLimit;

    private volatile LocalDate callDay = LocalDate.now();
    private final AtomicInteger callsToday = new AtomicInteger();

    public QuizBank(OpenAiChatClient client) {
        this.client = client;
    }

    public boolean aiAvailable() { return client != null && client.isConfigured(); }

    /**
     * 문제를 꺼낸다. 창고가 부족하면 그 자리에서 만들고, 그래도 모자라면 내장 문제로 채운다.
     *
     * @param level 1~10
     */
    public List<QuizQuestion> take(int level, int count) {
        int lv = Math.max(1, Math.min(10, level));
        Deque<QuizQuestion> q = deque(lv);

        List<QuizQuestion> out = new ArrayList<>();
        while (out.size() < count) {
            if (q.isEmpty() && !refill(lv)) break;      // 더 만들 수 없으면 중단
            if (q.isEmpty()) break;
            out.add(q.poll());
        }
        // 부족한 만큼은 내장 문제로 메운다(AI 미설정·한도 초과·응답 실패).
        if (out.size() < count) out.addAll(QuizFallback.pick(lv, count - out.size(), out));
        return out;
    }

    /** 다음 판을 위해 미리 채워둔다(플레이 중 끊김 방지). 부족할 때만 호출한다. */
    public void prewarm(int level) {
        int lv = Math.max(1, Math.min(10, level));
        if (deque(lv).size() <= LOW_WATER) refill(lv);
    }

    /**
     * 이미 만들어 둔 것만 꺼낸다 — AI를 부르지 않으므로 즉시 돌아온다.
     *
     * <p>무제한 모드가 진행 중에 문제를 보충할 때 쓴다. 모자라면 내장 문제로 메우고,
     * 배경에서 창고를 다시 채운다. 여기서 생성을 기다리면 게임이 멈춘다.
     */
    public List<QuizQuestion> takeReady(int level, int count, Collection<QuizQuestion> exclude) {
        int lv = Math.max(1, Math.min(10, level));
        Deque<QuizQuestion> q = deque(lv);
        List<QuizQuestion> out = new ArrayList<>();
        while (out.size() < count) {
            QuizQuestion x = q.poll();
            if (x == null) break;
            out.add(x);
        }
        prewarmAsync(lv);
        if (out.size() < count) {
            List<QuizQuestion> seenAll = new ArrayList<>(out);
            if (exclude != null) seenAll.addAll(exclude);
            out.addAll(QuizFallback.pick(lv, count - out.size(), seenAll));
        }
        return out;
    }

    /** 배경에서 창고를 채운다. 게임 스레드를 붙잡지 않는다. */
    public void prewarmAsync(int level) {
        int lv = Math.max(1, Math.min(10, level));
        if (deque(lv).size() > LOW_WATER || !aiAvailable()) return;
        AtomicBoolean busy = generating.computeIfAbsent(lv, k -> new AtomicBoolean());
        if (!busy.compareAndSet(false, true)) return;      // 이미 만들고 있다
        Thread t = new Thread(() -> {
            try { refill(lv); } catch (Exception e) {
                log.warn("퀴즈 배경 생성 실패: {}", e.getMessage());
            } finally { busy.set(false); }
        }, "quiz-gen-" + lv);
        t.setDaemon(true);
        t.start();
    }

    private Deque<QuizQuestion> deque(int level) {
        return pool.computeIfAbsent(level, k -> new ConcurrentLinkedDeque<>());
    }

    /** 한 번 호출해 창고를 채운다. 채웠으면 true. */
    private boolean refill(int level) {
        if (!aiAvailable() || !allowCall()) return false;
        List<QuizQuestion> made = generate(level);
        if (made.isEmpty()) return false;
        Deque<QuizQuestion> q = deque(level);
        for (QuizQuestion x : made) if (q.size() < POOL_MAX) q.add(x);
        return !q.isEmpty();
    }

    /** 하루 상한 확인 및 카운트. */
    private synchronized boolean allowCall() {
        LocalDate today = LocalDate.now();
        if (!today.equals(callDay)) { callDay = today; callsToday.set(0); }
        if (callsToday.get() >= dailyCallLimit) {
            log.warn("퀴즈 생성 하루 한도({}) 초과 — 내장 문제로 대체", dailyCallLimit);
            return false;
        }
        callsToday.incrementAndGet();
        return true;
    }

    // ── AI 생성 ──

    static final List<String> TOPICS = List.of(
            "한국사", "세계사", "동물", "식물", "나라·수도", "지리·자연", "과학", "우주·천문",
            "인체·의학", "음식", "스포츠", "미술", "음악", "문학", "신화·전설", "발명·기술",
            "경제·화폐", "언어·어원", "영화·드라마", "바다·해양", "곤충", "건축·유적", "공룡", "날씨");

    private List<QuizQuestion> generate(int level) {
        // 주제를 섞어 넣어 한 배치 안에서도 분야가 골고루 나오게 한다.
        List<String> topics = new ArrayList<>(TOPICS);
        Collections.shuffle(topics, ThreadLocalRandom.current());
        String picked = String.join(", ", topics.subList(0, Math.min(6, topics.size())));

        String user = "난이도 " + level + "/10 상식 문제 " + BATCH + "개를 만들어라.\n"
                + "이번 배치에서 다룰 주제(골고루 섞어라): " + picked + "\n"
                + difficultyHint(level) + "\n"
                + "객관식과 주관식을 섞어라(객관식 6, 주관식 4 정도).";

        String raw = client.complete(SYSTEM, user, 2400, 0.9, null, "퀴즈");
        if (raw == null || raw.isBlank()) return List.of();
        return parse(raw, level);
    }

    /** 난이도를 말로 풀어준다. 숫자만 주면 모델이 1과 3을 구분하지 못한다. */
    static String difficultyHint(int level) {
        return switch (Math.max(1, Math.min(10, level)) / 2) {
            case 0 -> "초등학생도 대부분 아는 아주 쉬운 상식.";                      // 1
            case 1 -> "초등학생 수준의 쉬운 상식.";                                 // 2~3
            case 2 -> "중학생 수준. 학교에서 배우는 기본 상식.";                      // 4~5
            case 3 -> "고등학생~일반 성인 수준. 조금 생각해야 풀린다.";                // 6~7
            case 4 -> "상식이 풍부한 성인도 헷갈리는 수준. 세부 사실을 묻는다.";        // 8~9
            default -> "해당 분야를 따로 공부한 사람만 아는 마니아 수준.";              // 10
        };
    }

    /** 응답 파싱. 형식이 어긋난 문제는 조용히 버린다(한 개가 깨져도 나머지는 쓴다). */
    List<QuizQuestion> parse(String raw, int level) {
        String body = raw.trim();
        // 모델이 ```json 으로 감싸는 경우가 있다.
        if (body.startsWith("```")) {
            int nl = body.indexOf('\n');
            body = nl > 0 ? body.substring(nl + 1) : body;
            if (body.endsWith("```")) body = body.substring(0, body.length() - 3);
        }
        int lb = body.indexOf('['), rb = body.lastIndexOf(']');
        if (lb < 0 || rb <= lb) return List.of();
        body = body.substring(lb, rb + 1);

        List<QuizQuestion> out = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(body);
            for (JsonNode n : arr) {
                QuizQuestion q = toQuestion(n, level);
                if (q == null) continue;
                String key = QuizQuestion.normalize(q.question());
                synchronized (seen) {
                    if (!seen.add(key)) continue;                 // 이미 낸 문제
                    if (seen.size() > SEEN_MAX) {                 // 오래된 것부터 잊는다
                        var it = seen.iterator();
                        for (int i = 0; i < SEEN_MAX / 4 && it.hasNext(); i++) { it.next(); it.remove(); }
                    }
                }
                out.add(q);
            }
        } catch (Exception e) {
            log.warn("퀴즈 응답 파싱 실패: {}", e.getMessage());
        }
        return out;
    }

    private QuizQuestion toQuestion(JsonNode n, int level) {
        String question = text(n, "question");
        String topic = text(n, "topic");
        String explain = text(n, "explain");
        String kindRaw = text(n, "kind").toUpperCase();
        if (question.isBlank()) return null;

        if (kindRaw.startsWith("T")) {
            List<String> answers = new ArrayList<>();
            JsonNode a = n.get("answers");
            if (a != null && a.isArray()) for (JsonNode x : a) {
                String s = x.asText("").trim();
                if (!s.isEmpty()) answers.add(s);
            }
            // 주관식은 답이 너무 길면(문장형) 맞히기가 사실상 불가능하다.
            if (answers.isEmpty() || answers.get(0).length() > 20) return null;
            return new QuizQuestion(id(question), topic, level, QuizQuestion.Kind.TEXT,
                    question, List.of(), -1, List.copyOf(answers), explain);
        }

        List<String> choices = new ArrayList<>();
        JsonNode c = n.get("choices");
        if (c != null && c.isArray()) for (JsonNode x : c) {
            String s = x.asText("").trim();
            if (!s.isEmpty()) choices.add(s);
        }
        int idx = n.path("answerIndex").asInt(-1);
        if (choices.size() != 4 || idx < 0 || idx > 3) return null;
        if (new java.util.HashSet<>(choices).size() != 4) return null;   // 보기 중복이면 버린다
        return new QuizQuestion(id(question), topic, level, QuizQuestion.Kind.CHOICE,
                question, List.copyOf(choices), idx, List.of(), explain);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null ? "" : v.asText("").trim();
    }

    private static String id(String question) {
        return Integer.toHexString(QuizQuestion.normalize(question).hashCode());
    }

    /** 테스트·진단용. */
    int poolSize(int level) {
        Deque<QuizQuestion> q = pool.get(level);
        return q == null ? 0 : q.size();
    }
    int callsToday() { return callsToday.get(); }

    // ── 고정 시스템 프롬프트(캐싱 프리픽스) ──
    static final String SYSTEM = """
            너는 한국어 상식 퀴즈 출제자다. 주어진 난이도와 주제로 퀴즈를 만들어 JSON 배열로만 답한다.

            [출력 형식 — 이것만 출력한다. 설명·머리말·코드펜스 금지]
            [
              {"kind":"CHOICE","topic":"한국사","question":"질문","choices":["보기1","보기2","보기3","보기4"],"answerIndex":0,"explain":"한 줄 해설"},
              {"kind":"TEXT","topic":"동물","question":"질문","answers":["대표답","다른 표기"],"explain":"한 줄 해설"}
            ]

            [반드시 지킬 것]
            - 사실만 낸다. 확실하지 않은 내용은 아예 내지 마라. 틀린 문제가 하나라도 있으면 게임이 망한다.
            - 정답이 단 하나로 정해지는 문제만 낸다. "가장 ~한 것은?" 처럼 해석에 따라 답이 갈리는 질문은 금지.
            - 최신 정보로 바뀔 수 있는 것(현직자, 최근 기록, 시세, 인구 순위)은 내지 마라. 변하지 않는 사실만.
            - 질문은 한 문장으로 40자 이내. 배경 설명을 길게 붙이지 마라.
            - explain은 왜 그게 답인지 한 줄(40자 이내)로. 정답을 그냥 반복하지 마라.

            [객관식(CHOICE) 규칙]
            - choices는 정확히 4개. 서로 겹치거나 같은 뜻이면 안 된다.
            - 오답도 그럴듯해야 한다. 누가 봐도 아닌 보기를 채우지 마라.
            - answerIndex는 0~3. 정답 위치를 매번 다르게 섞어라.
            - "위의 모두", "정답 없음" 같은 보기는 쓰지 마라.

            [주관식(TEXT) 규칙]
            - 답은 한 단어나 짧은 고유명사(10자 이내 권장, 20자 초과 금지).
            - answers에 통용되는 표기를 모두 넣어라. 예: ["이순신","충무공 이순신"], ["에펠탑","에펠 탑"],
              ["아마존강","아마존 강","아마존"], ["DNA","디엔에이"]. 이걸 빼먹으면 맞는 답이 오답 처리된다.
            - 숫자 답은 단위를 답에 넣지 말고 질문에 명시하라. 예: 질문 "...몇 개인가?(숫자만)" / answers ["8"].
            - 사람 이름은 성+이름 형태와 흔히 부르는 형태를 함께 넣어라.

            [난이도]
            - 난이도는 1~10이다. 1은 초등학생도 아는 것, 10은 그 분야를 따로 공부한 사람만 아는 것.
            - 난이도가 높다고 문제를 꼬지 마라. 지식의 깊이로만 어렵게 하라.

            [주제]
            - 요청받은 주제 안에서 골고루 낸다. 한 주제에 몰리지 않게 하라.
            - 특정 국가·문화에 치우치지 말고 한국과 세계를 섞어라.
            """;
}
