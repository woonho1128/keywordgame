package com.wordplay.feedback;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 건의사항 메일 발송(Resend HTTPS API).
 *
 * SMTP(25/465/587) 대신 HTTPS(443)를 쓴다 — 운영 서버(Oracle Cloud)는 아웃바운드 SMTP를
 * 기본 차단하므로 SMTP 방식은 배포 후 타임아웃이 난다.
 *
 * 키가 없으면 조용히 건너뛴다(로컬 개발에서 접수만 되도록).
 */
@Slf4j
@Component
public class FeedbackMailer {

    private static final String ENDPOINT = "https://api.resend.com/emails";

    /** 메일 서버가 늦게 응답해도 요청이 오래 붙잡히지 않도록 짧은 타임아웃을 준다. */
    private final RestClient http = RestClient.builder()
            .baseUrl(ENDPOINT)
            .requestFactory(timeouts())
            .build();

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(3));
        f.setReadTimeout(Duration.ofSeconds(5));
        return f;
    }

    @Value("${app.feedback.resend-api-key:}")
    private String apiKey;

    @Value("${app.feedback.mail-from:gg <onboarding@resend.dev>}")
    private String from;

    @Value("${app.feedback.mail-to:}")
    private String to;

    public boolean enabled() { return !apiKey.isBlank() && !to.isBlank(); }

    /** 발송 성공 여부. 실패해도 예외를 던지지 않는다(접수 자체는 성공시켜야 하므로). */
    public boolean send(Feedback f) {
        if (!enabled()) {
            log.info("[feedback] 메일 설정이 없어 발송 생략 (id={})", f.getId());
            return false;
        }
        try {
            String subject = "[gg 건의] " + label(f.getCategory()) + " · " + oneLine(f.getMessage(), 30);
            http.post()
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(Map.of(
                            "from", from,
                            "to", List.of(to),
                            "subject", subject,
                            "html", html(f)))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("[feedback] 메일 발송 실패 (id={}): {}", f.getId(), e.getMessage());
            return false;
        }
    }

    static String label(String category) {
        return switch (category == null ? "" : category) {
            case "BUG" -> "🐞 버그 제보";
            case "IDEA" -> "💡 건의";
            case "GAME" -> "🎮 새 게임 제안";
            default -> "💬 기타";
        };
    }

    private static String oneLine(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private static String html(Feedback f) {
        return """
                <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;max-width:640px">
                  <h2 style="margin:0 0 4px">%s</h2>
                  <p style="color:#64748b;margin:0 0 16px;font-size:13px">%s · %s</p>
                  <div style="white-space:pre-wrap;background:#f8fafc;border:1px solid #e2e8f0;border-radius:12px;padding:16px;font-size:15px;line-height:1.6">%s</div>
                  <table style="margin-top:16px;font-size:13px;color:#475569;border-collapse:collapse">
                    <tr><td style="padding:2px 12px 2px 0">보낸 사람</td><td><b>%s</b></td></tr>
                    <tr><td style="padding:2px 12px 2px 0">연락처</td><td>%s</td></tr>
                    <tr><td style="padding:2px 12px 2px 0">화면</td><td>%s</td></tr>
                    <tr><td style="padding:2px 12px 2px 0">브라우저</td><td style="color:#94a3b8">%s</td></tr>
                  </table>
                </div>
                """.formatted(
                label(f.getCategory()),
                esc(String.valueOf(f.getCreatedAt())), "gg.wonono1128.com",
                esc(f.getMessage()),
                esc(blankTo(f.getNickname(), "익명")),
                esc(blankTo(f.getContact(), "-")),
                esc(blankTo(f.getPage(), "-")),
                esc(blankTo(f.getUserAgent(), "-")));
    }

    private static String blankTo(String s, String alt) { return s == null || s.isBlank() ? alt : s; }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
