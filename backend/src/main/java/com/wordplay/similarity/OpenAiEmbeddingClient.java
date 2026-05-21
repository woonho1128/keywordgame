package com.wordplay.similarity;

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
 * OpenAI Embeddings API 호출 클라이언트.
 *
 * 단어 → 1536차원 벡터를 받아 L2 정규화 후 반환.
 * 비용: text-embedding-3-small 기준 $0.02 / 1M tokens.
 * 친구 규모 사용 시 사실상 무료.
 */
@Slf4j
@Component
public class OpenAiEmbeddingClient {

    private static final String URL = "https://api.openai.com/v1/embeddings";

    /** 모델은 사전(word_vectors.bin)과 동일해야 함. 다르면 벡터 공간이 안 맞음. */
    @Value("${app.openai.model:text-embedding-3-large}")
    private String model;

    @Value("${app.openai.api-key:}")
    private String apiKey;

    @Value("${app.openai.timeout-ms:10000}")
    private long timeoutMs;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 단일 단어 임베딩.
     * @return L2 정규화된 벡터, 실패 시 null
     */
    public float[] embed(String word) {
        List<float[]> result = embedBatch(List.of(word));
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * 배치 임베딩. OpenAI는 한 요청에 여러 input 받을 수 있음.
     * @return 입력 순서대로 L2 정규화된 벡터 리스트. 실패 시 빈 리스트.
     */
    public List<float[]> embedBatch(List<String> words) {
        if (!isConfigured()) {
            log.warn("OpenAI API key not configured");
            return List.of();
        }
        if (words.isEmpty()) return List.of();

        try {
            String body = mapper.writeValueAsString(Map.of(
                    "input", words,
                    "model", model
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
                log.warn("OpenAI embedding failed: {} body={}",
                        resp.statusCode(), truncate(resp.body(), 200));
                return List.of();
            }

            JsonNode root = mapper.readTree(resp.body());
            JsonNode data = root.path("data");
            List<float[]> out = new java.util.ArrayList<>(data.size());
            for (JsonNode item : data) {
                JsonNode emb = item.path("embedding");
                float[] vec = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) vec[i] = (float) emb.get(i).asDouble();
                normalize(vec);
                out.add(vec);
            }
            return out;
        } catch (Exception e) {
            log.warn("OpenAI embedding error for words={}: {}", truncate(words.toString(), 60), e.getMessage());
            return List.of();
        }
    }

    /** in-place L2 정규화 */
    private static void normalize(float[] v) {
        float norm = 0f;
        for (float x : v) norm += x * x;
        norm = (float) Math.sqrt(norm);
        if (norm == 0f) return;
        for (int i = 0; i < v.length; i++) v[i] /= norm;
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }
}
