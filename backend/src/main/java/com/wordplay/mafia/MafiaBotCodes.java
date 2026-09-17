package com.wordplay.mafia;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 마피아 AI 봇 추가용 1회성 코드 발급·검증(전역·인메모리).
 *
 * <p>마스터 봇 관리자 코드를 아는 사람이 1회성 코드를 발급받아, 마스터 코드를
 * 공유하지 않고도 남이 봇을 켤 수 있게 한다. 코드는 한 번 사용하면 소멸하고
 * 24시간 뒤 만료된다.
 */
@Component
public class MafiaBotCodes {

    private static final long TTL_MS = 24 * 60 * 60 * 1000L;  // 발급 후 24시간 유효
    private static final int MAX_OUTSTANDING = 200;           // 미사용 코드 상한
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray(); // 헷갈리는 0/O·1/I/L 제외

    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, Long> codes = new ConcurrentHashMap<>(); // 정규화 코드 -> 만료 시각

    /** 1회성 코드 발급. 표시용 형식(예: ABCD-EFGH)으로 반환. */
    public String issue() {
        purge();
        if (codes.size() >= MAX_OUTSTANDING)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "미사용 코드가 너무 많습니다. 잠시 후 다시 시도하세요");
        String raw;
        do { raw = random8(); } while (codes.containsKey(raw));
        codes.put(raw, System.currentTimeMillis() + TTL_MS);
        return raw.substring(0, 4) + "-" + raw.substring(4);
    }

    /** 유효한(미사용·미만료) 코드인지 확인만(소비하지 않음). */
    public boolean isValid(String code) {
        purge();
        Long exp = codes.get(norm(code));
        return exp != null && exp > System.currentTimeMillis();
    }

    /** 코드 1회 소비. 유효했으면 true. */
    public boolean consume(String code) {
        purge();
        Long exp = codes.remove(norm(code));
        return exp != null && exp > System.currentTimeMillis();
    }

    private void purge() {
        long now = System.currentTimeMillis();
        codes.entrySet().removeIf(e -> e.getValue() <= now);
    }

    private String random8() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)]);
        return sb.toString();
    }

    /** 대소문자·구분자 무시하고 매칭되도록 정규화. */
    private static String norm(String s) {
        return s == null ? "" : s.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }
}
