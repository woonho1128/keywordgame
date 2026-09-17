package com.wordplay.saju.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로컬 스텁 서버로 OpenAI 호출 경로를 검증한다.
 * 실제 API를 부르지 않으므로 비용도 키도 필요 없다.
 */
class SajuAiClientTest {

    private HttpServer server;
    private SajuAiClient client;

    /** 요청 본문 기록 — 재시도 시 무엇을 바꿔 보냈는지 확인용 */
    private final List<String> requestBodies = new ArrayList<>();

    /** 요청 순번 → 응답 (status, body) */
    private Function<Integer, int[]> statusPlan;
    private Function<Integer, String> bodyPlan;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();

        client = new SajuAiClient();
        ReflectionTestUtils.setField(client, "url",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        ReflectionTestUtils.setField(client, "model", "gpt-test");
        ReflectionTestUtils.setField(client, "timeoutMs", 5000L);
        ReflectionTestUtils.setField(client, "temperature", 0.85);
        ReflectionTestUtils.setField(client, "maxTokens", 100);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requestBodies.add(body);

        int attempt = requestBodies.size() - 1;
        int status = statusPlan.apply(attempt)[0];
        byte[] response = bodyPlan.apply(attempt).getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

    private void plan(int[] statuses, String[] bodies) {
        statusPlan = i -> new int[]{statuses[Math.min(i, statuses.length - 1)]};
        bodyPlan = i -> bodies[Math.min(i, bodies.length - 1)];
    }

    private static String chatResponse(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}]}";
    }

    @Test
    void 정상_응답이면_content를_돌려준다() {
        plan(new int[]{200}, new String[]{chatResponse("{\\\"summary\\\":\\\"좋아요\\\"}")});

        String result = client.completeJson("system", "user");

        assertThat(result).isEqualTo("{\"summary\":\"좋아요\"}");
        assertThat(requestBodies).hasSize(1);
        assertThat(requestBodies.get(0))
                .contains("\"model\":\"gpt-test\"")
                .contains("\"response_format\"")
                .contains("json_object");
    }

    @Test
    void max_tokens를_거부하는_모델이면_max_completion_tokens로_바꿔_재시도한다() {
        plan(
                new int[]{400, 200},
                new String[]{
                        "{\"error\":{\"message\":\"Unsupported parameter: 'max_tokens'. Use 'max_completion_tokens' instead.\"}}",
                        chatResponse("{}")
                }
        );

        String result = client.completeJson("system", "user");

        assertThat(result).isEqualTo("{}");
        assertThat(requestBodies).hasSize(2);
        assertThat(requestBodies.get(0)).contains("\"max_tokens\"");
        assertThat(requestBodies.get(1))
                .contains("\"max_completion_tokens\"")
                .doesNotContain("\"max_tokens\"");
    }

    @Test
    void temperature를_거부하면_빼고_재시도한다() {
        plan(
                new int[]{400, 200},
                new String[]{
                        "{\"error\":{\"message\":\"Unsupported value: 'temperature' does not support 0.85\"}}",
                        chatResponse("{}")
                }
        );

        assertThat(client.completeJson("system", "user")).isEqualTo("{}");
        assertThat(requestBodies.get(1)).doesNotContain("\"temperature\"");
    }

    @Test
    void 일시적인_5xx는_재시도한다() {
        plan(new int[]{503, 200}, new String[]{"{\"error\":\"overloaded\"}", chatResponse("{}")});

        assertThat(client.completeJson("system", "user")).isEqualTo("{}");
        assertThat(requestBodies).hasSize(2);
    }

    @Test
    void 인증_실패는_재시도하지_않고_null() {
        plan(new int[]{401}, new String[]{"{\"error\":{\"message\":\"Invalid API key\"}}"});

        assertThat(client.completeJson("system", "user")).isNull();
        assertThat(requestBodies).hasSize(1);
    }

    @Test
    void 모델이_거절하면_null() {
        plan(new int[]{200}, new String[]{
                "{\"choices\":[{\"message\":{\"refusal\":\"거절합니다\",\"content\":null}}]}"});

        assertThat(client.completeJson("system", "user")).isNull();
    }

    @Test
    void API_키가_없으면_호출하지_않는다() {
        ReflectionTestUtils.setField(client, "apiKey", "");

        assertThat(client.isConfigured()).isFalse();
        assertThat(client.completeJson("system", "user")).isNull();
        assertThat(requestBodies).isEmpty();
    }
}
