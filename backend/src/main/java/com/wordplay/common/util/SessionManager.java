package com.wordplay.common.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    public String getOrCreate(HttpServletRequest req, HttpServletResponse res) {
        String existing = extract(req);
        if (existing != null) return existing;

        String fresh = UUID.randomUUID().toString().replace("-", "");
        write(res, fresh);
        return fresh;
    }

    public String extract(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (COOKIE_NAME.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private void write(HttpServletResponse res, String value) {
        Cookie cookie = new Cookie(COOKIE_NAME, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(false);   // 운영(HTTPS)에서는 true로
        cookie.setPath("/");
        cookie.setMaxAge(MAX_AGE_DAYS * 24 * 60 * 60);
        // SameSite=Lax — Servlet API에 직접 setter 없음. 헤더로 추가
        res.addCookie(cookie);
        res.addHeader("Set-Cookie",
                String.format("%s=%s; Path=/; Max-Age=%d; HttpOnly; SameSite=Lax",
                        COOKIE_NAME, value, MAX_AGE_DAYS * 24 * 60 * 60));
    }
}
