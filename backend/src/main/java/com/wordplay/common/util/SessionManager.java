package com.wordplay.common.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 쿠키 기반 session_key 관리.
 * - 게임 시작 시 쿠키가 없으면 새로 발급
 * - 추측/포기 요청 시 쿠키에서 추출
 */
@Component
public class SessionManager {

    public static final String COOKIE_NAME = "wp_session";
    private static final int MAX_AGE_DAYS = 30;

    /** 운영(HTTPS)에선 true. SPRING_PROFILES_ACTIVE=prod일 때 또는 COOKIE_SECURE=true 환경변수로 override */
    @Value("${app.cookie.secure:false}")
    private boolean cookieSecure;

    /** Cross-site 요청(다른 도메인 프론트 → 백엔드 API)에선 SameSite=None 필요 */
    @Value("${app.cookie.same-site:Lax}")
    private String cookieSameSite;

    public String getOrCreate(HttpServletRequest req, HttpServletResponse res) {
        String existing = extract(req);
        if (existing != null) return existing;

        String fresh = UUID.randomUUID().toString().replace("-", "");
        write(res, fresh);
        return fresh;
    }

    public static final String HEADER_NAME = "X-Session-Key";

    public String extract(HttpServletRequest req) {
        // 1순위: 헤더 (proxy/HTTP 환경에서도 안전)
        String h = req.getHeader(HEADER_NAME);
        if (h != null && !h.isEmpty()) return h;

        // 2순위: 쿠키 (fallback, 동일 origin HTTPS 환경에서 자연스러움)
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (COOKIE_NAME.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private void write(HttpServletResponse res, String value) {
        // SameSite는 Servlet API에 직접 setter 없으므로 헤더로 직접 작성.
        // Cross-site에서 쿠키 보내려면 Secure + SameSite=None 둘 다 필요.
        StringBuilder sb = new StringBuilder()
                .append(COOKIE_NAME).append("=").append(value)
                .append("; Path=/")
                .append("; Max-Age=").append(MAX_AGE_DAYS * 24 * 60 * 60)
                .append("; HttpOnly");
        if (cookieSecure) sb.append("; Secure");
        sb.append("; SameSite=").append(cookieSameSite);
        res.addHeader("Set-Cookie", sb.toString());
    }
}
