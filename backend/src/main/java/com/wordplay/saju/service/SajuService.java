package com.wordplay.saju.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.util.NanoIdGenerator;
import com.wordplay.saju.client.OpenAiChatClient;
import com.wordplay.saju.domain.FourPillars;
import com.wordplay.saju.domain.SajuType;
import com.wordplay.saju.dto.SajuChart;
import com.wordplay.saju.dto.SajuReadingResponse;
import com.wordplay.saju.dto.SajuRequest;
import com.wordplay.saju.dto.SajuResult;
import com.wordplay.saju.dto.SajuTypeItem;
import com.wordplay.saju.entity.SajuReading;
import com.wordplay.saju.repository.SajuReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * AI 사주 서비스.
 *
 * <p>흐름: 입력 검증 → 만세력 계산(결정론) → 같은 입력 캐시 확인 → AI 해석 → 저장.
 * 사주팔자는 절대 AI에게 계산시키지 않는다. LLM은 60갑자·절기를 자주 틀린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SajuService {

    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");

    private final SajuCalculator calculator;
    private final SajuPromptBuilder promptBuilder;
    private final OpenAiChatClient chatClient;
    private final SajuRateLimiter rateLimiter;
    private final SajuReadingRepository readingRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.saju.id-length:8}")
    private int readingIdLength;

    /** 같은 사람이 같은 종류를 다시 보면 이전 해석을 그대로 준다 (사주는 바뀌지 않으니까) */
    @Value("${app.saju.reuse-same-input:true}")
    private boolean reuseSameInput;

    /** 화면에 뿌릴 사주 종류 목록 */
    public List<SajuTypeItem> listTypes() {
        return Arrays.stream(SajuType.values()).map(SajuTypeItem::from).toList();
    }

    public boolean isAvailable() {
        return chatClient.isConfigured();
    }

    @Transactional
    public SajuReadingResponse createReading(SajuRequest req, String sessionKey) {
        LocalTime birthTime = req.effectiveTime();
        FourPillars pillars = calculatePillars(req.birthDate(), birthTime, req);

        String cacheKey = cacheKey(req, birthTime);
        if (reuseSameInput) {
            var cached = readingRepository.findFirstByCacheKeyOrderByCreatedAtDesc(cacheKey);
            if (cached.isPresent()) {
                log.info("Saju cache hit: type={} key={}", req.sajuType(), cacheKey.substring(0, 8));
                return toResponse(cached.get());
            }
        }

        if (!chatClient.isConfigured()) {
            throw new BusinessException(ErrorCode.SAJU_AI_UNAVAILABLE);
        }
        if (!rateLimiter.tryAcquire(sessionKey)) {
            throw new BusinessException(ErrorCode.SAJU_RATE_LIMITED);
        }

        SajuResult result;
        try {
            result = askAi(req, pillars);
        } catch (RuntimeException e) {
            rateLimiter.release(sessionKey);
            throw e;
        }

        SajuChart chart = SajuChart.from(pillars);
        SajuReading saved = readingRepository.save(SajuReading.builder()
                .readingId(generateUniqueId())
                .sajuType(req.sajuType())
                .nickname(trimToNull(req.nickname()))
                .birthDate(req.birthDate())
                .birthTime(birthTime)
                .timeUnknown(birthTime == null)
                .gender(req.gender())
                .chartJson(toJson(chart))
                .resultJson(toJson(result))
                .aiModel(chatClient.model())
                .cacheKey(cacheKey)
                .build());

        return toResponse(saved);
    }

    @Transactional
    public SajuReadingResponse getReading(String readingId) {
        SajuReading reading = readingRepository.findById(readingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SAJU_NOT_FOUND));
        reading.setViewCount(reading.getViewCount() + 1);
        return toResponse(reading);
    }

    private FourPillars calculatePillars(LocalDate birthDate, LocalTime birthTime, SajuRequest req) {
        if (birthDate.isAfter(LocalDate.now())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "생년월일이 미래로 되어 있어요");
        }
        try {
            return calculator.calculate(birthDate, birthTime, req.gender(), LocalDate.now());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    private SajuResult askAi(SajuRequest req, FourPillars pillars) {
        String json = chatClient.completeJson(
                promptBuilder.systemPrompt(),
                promptBuilder.userPrompt(req.sajuType(), req.nickname(), req.gender(), pillars)
        );
        if (json == null) {
            throw new BusinessException(ErrorCode.SAJU_AI_FAILED);
        }

        SajuResult result;
        try {
            result = objectMapper.readValue(json, SajuResult.class);
        } catch (Exception e) {
            log.warn("Saju AI response parse failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.SAJU_AI_FAILED);
        }
        if (!result.isUsable()) {
            log.warn("Saju AI response missing required fields");
            throw new BusinessException(ErrorCode.SAJU_AI_FAILED);
        }
        return result;
    }

    private SajuReadingResponse toResponse(SajuReading reading) {
        SajuType type = reading.getSajuType();
        return new SajuReadingResponse(
                reading.getReadingId(),
                "/saju/" + reading.getReadingId(),
                type.name(),
                type.label(),
                type.emoji(),
                reading.getNickname(),
                reading.getBirthDate(),
                reading.getBirthTime() == null ? "시간 모름" : reading.getBirthTime().format(TIME_LABEL),
                reading.getGender().korean(),
                fromJson(reading.getChartJson(), SajuChart.class),
                fromJson(reading.getResultJson(), SajuResult.class),
                reading.getCreatedAt()
        );
    }

    /**
     * 같은 입력인지 판단하는 키 — 프롬프트에 들어가는 값을 모두 넣는다.
     * 나이와 세운은 해가 바뀌면 달라지므로 기준 연도도 키에 포함한다.
     * (안 넣으면 "올해의 운세"가 내년에도 작년 해석을 돌려준다)
     */
    private String cacheKey(SajuRequest req, LocalTime birthTime) {
        String raw = String.join("|",
                req.sajuType().name(),
                trimToNull(req.nickname()) == null ? "" : req.nickname().trim(),
                req.birthDate().toString(),
                birthTime == null ? "unknown" : birthTime.toString(),
                req.gender().name(),
                String.valueOf(LocalDate.now().getYear())
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "캐시 키 생성에 실패했습니다");
        }
    }

    private String generateUniqueId() {
        for (int i = 0; i < 5; i++) {
            String id = NanoIdGenerator.generate(readingIdLength);
            if (!readingRepository.existsById(id)) return id;
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to generate unique readingId");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "사주 결과 저장에 실패했습니다");
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "사주 결과를 읽지 못했습니다");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
