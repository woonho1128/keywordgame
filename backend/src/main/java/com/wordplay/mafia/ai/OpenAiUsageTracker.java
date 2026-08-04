package com.wordplay.mafia.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenAI 토큰 사용량 집계.
 *
 * <p>대시보드는 반영이 늦고 모델별 비교도 번거로워서, 응답에 실려 오는 usage 를 그대로
 * 로그에 남기고 누적치를 들고 있는다. 모델을 바꿔가며 비교하거나 추론 토큰이 실제로
 * 얼마나 붙는지 확인할 때 즉시 쓸 수 있다.
 *
 * <p>비용은 설정된 단가(1M 토큰당 달러)로 계산한 어림값이다. 캐시된 입력 토큰은 단가가
 * 달라 별도 단가를 둔다. 정산 금액은 OpenAI 청구서가 기준이다.
 */
@Slf4j
@Component
public class OpenAiUsageTracker {

    /** 1M 토큰당 달러. 기본값은 gpt-5.6-luna 기준. */
    @Value("${app.openai.price-input-per-mtok:0.20}")
    private double priceInput;

    @Value("${app.openai.price-cached-input-per-mtok:0.02}")
    private double priceCachedInput;

    @Value("${app.openai.price-output-per-mtok:1.20}")
    private double priceOutput;

    /** 로그에 원화를 같이 찍을 때 쓰는 환율(정확한 정산용이 아니라 감 잡기용). */
    @Value("${app.openai.krw-per-usd:1400}")
    private double krwPerUsd;

    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong cachedTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong reasoningTokens = new AtomicLong();
    private volatile LocalDate day = today();

    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Seoul")); }

    /** 한 번의 호출 결과를 기록하고 한 줄 로그를 남긴다. */
    public void record(String kind, String model, long input, long cached, long output, long reasoning) {
        rollDayIfNeeded();
        calls.incrementAndGet();
        inputTokens.addAndGet(input);
        cachedTokens.addAndGet(cached);
        outputTokens.addAndGet(output);
        reasoningTokens.addAndGet(reasoning);

        double usd = costUsd(input, cached, output);
        log.info("[openai-usage] {} model={} 입력={}(캐시 {}) 출력={}(추론 {}) ≈{}원 | 오늘 누적: {}회 ≈{}원",
                kind, model, input, cached, output, reasoning, krw(usd),
                calls.get(), krw(totalUsd()));
    }

    /** 입력·출력 토큰으로 어림 비용(달러). cached 는 input 에 포함된 값으로 본다. */
    double costUsd(long input, long cached, long output) {
        long fresh = Math.max(0, input - cached);
        return fresh / 1e6 * priceInput + cached / 1e6 * priceCachedInput + output / 1e6 * priceOutput;
    }

    public double totalUsd() {
        return costUsd(inputTokens.get(), cachedTokens.get(), outputTokens.get());
    }

    /** 오늘 누적 요약(관리자 조회용). */
    public synchronized String summary() {
        rollDayIfNeeded();
        long c = calls.get();
        if (c == 0) return "오늘(" + day + ") 호출 없음";
        return String.format(
                "오늘(%s) 호출 %d회 · 입력 %,d(캐시 %,d) · 출력 %,d(추론 %,d) · 어림비용 $%.4f (약 %s원)",
                day, c, inputTokens.get(), cachedTokens.get(), outputTokens.get(), reasoningTokens.get(),
                totalUsd(), krw(totalUsd()));
    }

    /** 날짜가 바뀌면 누적치를 초기화하고 전날 합계를 로그로 남긴다. */
    private synchronized void rollDayIfNeeded() {
        LocalDate now = today();
        if (now.equals(day)) return;
        if (calls.get() > 0)
            log.info("[openai-usage] {} 마감 · 호출 {}회 · 입력 {} · 출력 {} · 추론 {} ≈{}원",
                    day, calls.get(), inputTokens.get(), outputTokens.get(), reasoningTokens.get(), krw(totalUsd()));
        day = now;
        calls.set(0); inputTokens.set(0); cachedTokens.set(0); outputTokens.set(0); reasoningTokens.set(0);
    }

    private String krw(double usd) { return String.format("%,.1f", usd * krwPerUsd); }
}
