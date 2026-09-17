package com.wordplay.saju.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 사주 AI 호출 횟수 제한 — 지갑 보호용.
 *
 * <p>게임 쪽은 Rate Limit을 걷어냈지만(설계서 v1.1) 사주는 호출마다 실제 돈이 나가서
 * 최소한의 방어는 둔다. Redis 없이 in-memory 카운터로 처리하며, 서버가 재시작되면
 * 초기화된다. 친구 단위 서비스라 이 정도면 충분하다.
 */
@Slf4j
@Component
public class SajuRateLimiter {

    /** 세션(브라우저) 하나가 하루에 볼 수 있는 사주 수 */
    @Value("${app.saju.limit-per-session:10}")
    private int limitPerSession;

    /** 서비스 전체 하루 한도 */
    @Value("${app.saju.limit-total:300}")
    private int limitTotal;

    private final Map<String, AtomicInteger> perSession = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private volatile Instant windowStart = Instant.now();

    /**
     * 한도 안이면 카운터를 올리고 true.
     * AI 호출 직전에만 부른다 (캐시 적중이면 호출하지 않으므로 세지 않는다).
     */
    public synchronized boolean tryAcquire(String sessionKey) {
        rollWindowIfExpired();

        if (total.get() >= limitTotal) {
            log.warn("Saju daily total limit reached: {}", limitTotal);
            return false;
        }
        AtomicInteger count = perSession.computeIfAbsent(
                sessionKey == null ? "anonymous" : sessionKey, k -> new AtomicInteger());
        if (count.get() >= limitPerSession) {
            return false;
        }

        count.incrementAndGet();
        total.incrementAndGet();
        return true;
    }

    /** AI 호출이 실패했으면 카운터를 되돌린다 */
    public synchronized void release(String sessionKey) {
        AtomicInteger count = perSession.get(sessionKey == null ? "anonymous" : sessionKey);
        if (count != null && count.get() > 0) count.decrementAndGet();
        if (total.get() > 0) total.decrementAndGet();
    }

    private void rollWindowIfExpired() {
        if (Duration.between(windowStart, Instant.now()).toHours() >= 24) {
            perSession.clear();
            total.set(0);
            windowStart = Instant.now();
        }
    }
}
