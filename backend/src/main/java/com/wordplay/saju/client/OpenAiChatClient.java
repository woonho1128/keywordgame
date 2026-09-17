package com.wordplay.saju.client;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 호출 클라이언트 (JSON 응답 전용).
 *
 * <p>임베딩 클라이언트({@code OpenAiEmbeddingClient})와 같은 API 키를 쓴다.
 * 엔드포인트를 {@code app.openai.chat-url} 로 바꿀 수 있으므로 OpenAI 호환 API라면
 * 다른 게이트웨이로도 붙일 수 있다.
 *
 * <p>비용: gpt-4o-mini 기준 사주 1건에 대략 $0.001 미만. 친구 규모에선 사실상 무료.
 */
@Slf4j
@Component
public class OpenAiChatClient {

    @Value("${app.openai.chat-url:https://api.openai.com/v1/chat/completions}")
    private String url;

    @Value("${app.openai.chat-model:gpt-4o-mini}")
    private String model;

    @Value("${app.openai.api-key:}")
    private String apiKey;

    @Value("${app.openai.chat-timeout-ms:60000}")
    private long timeoutMs;

    @Value("${app.openai.chat-temperature:0.85}")
    private double temperature;

    @Value("${app.openai.chat-max-tokens:2000}")
    private int maxTokens;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String model() {
        return model;
    }

    /**
     * JSON 한 덩어리를 돌려받는 호출.
     *
     * @return 모델이 만든 JSON 문자열. 실패하면 null
     */
    public String completeJson(String systemPrompt, String userPrompt) {
        if (!isConfigured()) {
            log.warn("OpenAI API key not configured — saju reading unavailable");
            return null;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("response_format", Map.of("type", "json_object"));

        // 400은 파라미터 호환 문제일 수 있어 한 번 고쳐서 재시도, 429/5xx는 잠깐 쉬고 재시도
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpResponse<String> resp = send(body);
            if (resp == null) return null;

            if (resp.statusCode() == 200) {
                return extractContent(resp.body());
            }

            if (resp.statusCode() == 400 && adaptForModel(body, resp.body())) {
                continue;
            }
            if (resp.statusCode() == 429 || resp.statusCode() >= 500) {
                sleep(500L * (attempt + 1));
                continue;
            }

            log.warn("OpenAI chat failed: {} body={}", resp.statusCode(), truncate(resp.body(), 300));
            return null;
        }
        log.warn("OpenAI chat gave up after retries");
        return null;
    }

    private HttpResponse<String> send(Map<String, Object> body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("OpenAI chat error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 모델별 파라미터 차이를 흡수한다. 최신 모델은 max_tokens 대신
     * max_completion_tokens 를 받고, temperature 기본값 외 값을 거부하기도 한다.
     *
     * @return 요청을 고쳤으면 true (재시도할 가치가 있음)
     */
    private boolean adaptForModel(Map<String, Object> body, String errorBody) {
        String message = errorBody == null ? "" : errorBody;

        if (message.contains("max_completion_tokens") && body.containsKey("max_tokens")) {
            body.put("max_completion_tokens", body.remove("max_tokens"));
            log.info("OpenAI chat: switching to max_completion_tokens for model {}", model);
            return true;
        }
        if (message.contains("'temperature'") && body.containsKey("temperature")) {
            body.remove("temperature");
            log.info("OpenAI chat: dropping unsupported temperature for model {}", model);
            return true;
        }
        return false;
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode message = mapper.readTree(responseBody).path("choices").path(0).path("message");
            if (!message.path("refusal").isNull() && !message.path("refusal").isMissingNode()) {
                log.warn("OpenAI chat refused: {}", truncate(message.path("refusal").asText(), 200));
                return null;
            }
            String content = message.path("content").asText(null);
            return content == null || content.isBlank() ? null : content;
        } catch (Exception e) {
            log.warn("OpenAI chat parse error: {}", e.getMessage());
            return null;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }
}
