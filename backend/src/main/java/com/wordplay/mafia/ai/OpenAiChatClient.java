package com.wordplay.mafia.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI Chat Completions 호출 클라이언트 (마피아 AI 봇 전용).
 *
 * 임베딩 클라이언트와 같은 {@code OPENAI_API_KEY}를 재사용한다.
 * system 메시지는 매 호출 동일한 "고정 프리픽스"로 두어 OpenAI 프롬프트 캐싱
 * (1024토큰 이상 프리픽스 자동 캐시)의 이득을 받는다.
 *
 * <p>모델은 추론 계열(gpt-5.x, o 시리즈)과 일반 계열로 요청 규격이 다르다.
 * 추론 계열은 {@code max_completion_tokens}를 쓰고 {@code temperature}를 받지 않으며,
 * 숨은 추론 토큰이 토큰 상한을 먼저 소모한다. 그래서 여유분을 더해 보내고 노력 수준을
 * 최소로 둔다 — 봇이 내놓는 건 한 줄 대사와 번호 하나뿐이라 깊은 추론이 이득이 없고,
 * 추론 토큰은 출력 요금으로 과금되며 응답도 느려진다.
 */
@Slf4j
@Component
public class OpenAiChatClient {

    private static final String URL = "https://api.openai.com/v1/chat/completions";

    @Value("${app.openai.api-key:}")
    private String apiKey;

    @Value("${app.openai.chat-model:gpt-5.6-luna}")
    private String model;

    @Value("${app.openai.chat-timeout-ms:15000}")
    private long timeoutMs;

    /** 추론 모델일 때 노력 수준. 빈 값이거나 모르는 값이면 파라미터를 보내지 않는다(모델 기본값 사용). */
    @Value("${app.openai.chat-reasoning-effort:none}")
    private String reasoningEffort;

    /** 허용되는 노력 수준. 목록에 없는 값을 보내면 400이 나고 봇이 통째로 침묵한다. */
    private static final java.util.Set<String> EFFORTS =
            java.util.Set.of("none", "low", "medium", "high", "xhigh", "max");

    /** 실제로 보낼 노력 수준. 유효하지 않으면 null(=파라미터 생략). */
    String effortOrNull() {
        if (reasoningEffort == null) return null;
        String e = reasoningEffort.trim().toLowerCase();
        if (e.isEmpty()) return null;
        if (!EFFORTS.contains(e)) {
            log.warn("알 수 없는 reasoning_effort '{}' — 파라미터를 생략한다(허용: {})", reasoningEffort, EFFORTS);
            return null;
        }
        return e;
    }

    /** 추론 모델에서 숨은 추론 토큰이 답변 예산을 먹지 않도록 더해주는 여유분. */
    @Value("${app.openai.chat-reasoning-headroom:2000}")
    private int reasoningHeadroom;

    /**
     * 추론 토큰을 쓰는 모델인지. gpt-5 계열과 o 시리즈는 파라미터 규격이 달라 분기해야 한다.
     * 새 모델명이 나와도 환경변수로 강제할 수 있게 열어 둔다(auto/true/false).
     */
    @Value("${app.openai.chat-reasoning:auto}")
    private String reasoningMode;

    boolean reasoningModel() {
        if ("true".equalsIgnoreCase(reasoningMode)) return true;
        if ("false".equalsIgnoreCase(reasoningMode)) return false;
        String m = model == null ? "" : model.toLowerCase();
        return m.startsWith("gpt-5") || m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4");
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 한 번의 대화 완성 호출.
     *
     * @param system      고정 시스템 프롬프트(캐싱 프리픽스)
     * @param user        가변 사용자 메시지(현재 상황)
     * @param maxTokens   응답 최대 토큰
     * @param temperature 창의성
     * @return 어시스턴트 응답 문자열(트림). 실패 시 null.
     */
    public String complete(String system, String user, int maxTokens, double temperature) {
        if (!isConfigured()) return null;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", user)
            ));

            if (reasoningModel()) {
                /*
                 * 추론 모델(GPT-5 계열 등) 대응.
                 * - 토큰 상한 파라미터 이름이 max_completion_tokens 로 다르다.
                 * - 숨은 추론 토큰이 이 상한을 먼저 소모하므로, 눈에 보이는 답변 길이만큼만
                 *   주면 추론에 다 쓰이고 빈 응답이 온다. 그래서 여유분을 더해준다.
                 * - temperature 는 기본값만 허용하는 경우가 많아 아예 보내지 않는다.
                 * - 마피아 봇은 한 줄 대사/번호 하나라 깊은 추론이 필요 없다. 추론 토큰은
                 *   출력 요금으로 과금되고 응답도 느려지므로 노력 수준을 최소로 둔다.
                 */
                payload.put("max_completion_tokens", maxTokens + reasoningHeadroom);
                String effort = effortOrNull();
                if (effort != null) payload.put("reasoning_effort", effort);
            } else {
                payload.put("max_tokens", maxTokens);
                payload.put("temperature", temperature);
            }
            String body = mapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(URL))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("OpenAI chat failed: {} body={}", resp.statusCode(), truncate(resp.body(), 200));
                return null;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode choice = root.path("choices").path(0);
            JsonNode content = choice.path("message").path("content");
            String text = content.isMissingNode() || content.isNull() ? "" : content.asText().trim();
            if (text.isEmpty()) {
                // 추론 토큰이 예산을 다 먹으면 여기로 온다 — 조용히 폴백되지 않도록 사유를 남긴다.
                log.warn("OpenAI chat 빈 응답 (model={}, finish={}, 추론토큰={}) — reasoning-headroom 상향 필요할 수 있음",
                        model, choice.path("finish_reason").asText("?"),
                        root.path("usage").path("completion_tokens_details").path("reasoning_tokens").asInt(-1));
                return null;
            }
            return text;
        } catch (Exception e) {
            log.warn("OpenAI chat error: {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }
}
