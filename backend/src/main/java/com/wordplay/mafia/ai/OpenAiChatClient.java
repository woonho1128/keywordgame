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
import java.util.Map;

/**
 * OpenAI Chat Completions 호출 클라이언트 (마피아 AI 봇 전용).
 *
 * 임베딩 클라이언트와 같은 {@code OPENAI_API_KEY}를 재사용한다.
 * 모델은 소형(gpt-4o-mini) 기준. system 메시지는 매 호출 동일한 "고정 프리픽스"로
 * 두어 OpenAI 프롬프트 캐싱(1024토큰 이상 프리픽스 자동 캐시)의 이득을 받는다.
 */
@Slf4j
@Component
public class OpenAiChatClient {

    private static final String URL = "https://api.openai.com/v1/chat/completions";

    @Value("${app.openai.api-key:}")
    private String apiKey;

    @Value("${app.openai.chat-model:gpt-4o-mini}")
    private String model;

    @Value("${app.openai.chat-timeout-ms:15000}")
    private long timeoutMs;

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
            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "temperature", temperature,
                    "messages", List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user)
                    )
            ));
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
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) return null;
            String text = content.asText().trim();
            return text.isEmpty() ? null : text;
        } catch (Exception e) {
            log.warn("OpenAI chat error: {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }
}
