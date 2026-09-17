package com.wordplay.saju.service;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.saju.client.SajuAiClient;
import com.wordplay.saju.domain.CompatType;
import com.wordplay.saju.domain.Compatibility;
import com.wordplay.saju.domain.FourPillars;
import com.wordplay.saju.dto.CompatAnalysis;
import com.wordplay.saju.dto.CompatRequest;
import com.wordplay.saju.dto.CompatResponse;
import com.wordplay.saju.dto.CompatResult;
import com.wordplay.saju.dto.CompatTypeItem;
import com.wordplay.saju.dto.PersonInput;
import com.wordplay.saju.entity.SajuCompat;
import com.wordplay.saju.repository.SajuCompatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

/**
 * 궁합 서비스.
 *
 * <p>흐름: 두 사람 사주 계산 → 합·충 관계와 점수 계산 → 같은 조합 캐시 확인 → AI 해석 → 저장.
 * 점수와 관계 판정은 전부 {@link CompatibilityAnalyzer}가 하고, AI는 그걸 말로 풀기만 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SajuCompatService {

    /** 프롬프트·응답 스키마 버전 — 형식을 바꾸면 올린다 (캐시 키에 들어간다) */
    private static final String PROMPT_VERSION = "v1";

    private static final String DEFAULT_A_NAME = "첫 번째 분";
    private static final String DEFAULT_B_NAME = "두 번째 분";

    private final SajuCalculator calculator;
    private final CompatibilityAnalyzer analyzer;
    private final SajuPromptBuilder promptBuilder;
    private final SajuAiClient chatClient;
    private final SajuRateLimiter rateLimiter;
    private final SajuCompatRepository compatRepository;
    private final SajuSupport support;

    @Value("${app.saju.id-length:8}")
    private int idLength;

    @Value("${app.saju.reuse-same-input:true}")
    private boolean reuseSameInput;

    public List<CompatTypeItem> listTypes() {
        return Arrays.stream(CompatType.values()).map(CompatTypeItem::from).toList();
    }

    @Transactional
    public CompatResponse createCompat(CompatRequest req, String sessionKey) {
        FourPillars a = calculate(req.personA());
        FourPillars b = calculate(req.personB());

        String cacheKey = cacheKey(req);
        if (reuseSameInput) {
            var cached = compatRepository.findFirstByCacheKeyOrderByCreatedAtDesc(cacheKey);
            if (cached.isPresent()) {
                log.info("Compat cache hit: type={} key={}", req.compatType(), cacheKey.substring(0, 8));
                return toResponse(cached.get());
            }
        }

        if (!chatClient.isConfigured()) {
            throw new BusinessException(ErrorCode.SAJU_AI_UNAVAILABLE);
        }
        if (!rateLimiter.tryAcquire(sessionKey)) {
            throw new BusinessException(ErrorCode.SAJU_RATE_LIMITED);
        }

        Compatibility compatibility = analyzer.analyze(a, b);
        String aName = req.personA().displayName(DEFAULT_A_NAME);
        String bName = req.personB().displayName(DEFAULT_B_NAME);

        CompatResult result;
        try {
            result = askAi(req.compatType(), aName, req.personA(), a, bName, req.personB(), b, compatibility);
        } catch (RuntimeException e) {
            rateLimiter.release(sessionKey);
            throw e;
        }

        CompatAnalysis analysis = CompatAnalysis.of(aName, a, bName, b, compatibility);
        SajuCompat saved = compatRepository.save(SajuCompat.builder()
                .compatId(support.generateId(idLength, compatRepository::existsById))
                .compatType(req.compatType())
                .aNickname(SajuSupport.trimToNull(req.personA().nickname()))
                .aBirthDate(req.personA().birthDate())
                .aBirthTime(req.personA().effectiveTime())
                .aGender(req.personA().gender())
                .bNickname(SajuSupport.trimToNull(req.personB().nickname()))
                .bBirthDate(req.personB().birthDate())
                .bBirthTime(req.personB().effectiveTime())
                .bGender(req.personB().gender())
                .score(compatibility.score())
                .analysisJson(support.toJson(analysis))
                .resultJson(support.toJson(result))
                .aiModel(chatClient.model())
                .cacheKey(cacheKey)
                .build());

        return toResponse(saved);
    }

    @Transactional
    public CompatResponse getCompat(String compatId) {
        SajuCompat compat = compatRepository.findById(compatId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SAJU_NOT_FOUND));
        compat.setViewCount(compat.getViewCount() + 1);
        return toResponse(compat);
    }

    private FourPillars calculate(PersonInput person) {
        LocalDate birthDate = person.birthDate();
        if (birthDate.isAfter(LocalDate.now())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "생년월일이 미래로 되어 있어요");
        }
        try {
            return calculator.calculate(birthDate, person.effectiveTime(), person.gender(), LocalDate.now());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    private CompatResult askAi(CompatType type,
                               String aName, PersonInput aInput, FourPillars a,
                               String bName, PersonInput bInput, FourPillars b,
                               Compatibility compatibility) {
        String json = chatClient.completeJson(
                promptBuilder.compatSystemPrompt(),
                promptBuilder.compatUserPrompt(type,
                        aName, aInput.gender(), a,
                        bName, bInput.gender(), b,
                        compatibility)
        );
        if (json == null) {
            // 원인(HTTP 상태·본문)은 SajuAiClient 가 남긴다. 여기선 어떤 기능이 실패했는지만
            log.warn("Compat AI call returned nothing — check the OpenAI chat log above");
            throw new BusinessException(ErrorCode.SAJU_AI_FAILED);
        }

        CompatResult result = support.parseAiJson(json, CompatResult.class);
        if (!result.isUsable()) {
            log.warn("Compat AI response missing required fields");
            throw new BusinessException(ErrorCode.SAJU_AI_FAILED);
        }
        return result;
    }

    private CompatResponse toResponse(SajuCompat compat) {
        CompatType type = compat.getCompatType();
        return new CompatResponse(
                compat.getCompatId(),
                "/saju/compat/" + compat.getCompatId(),
                type.name(),
                type.label(),
                type.emoji(),
                support.fromJson(compat.getAnalysisJson(), CompatAnalysis.class),
                support.fromJson(compat.getResultJson(), CompatResult.class),
                compat.getCreatedAt()
        );
    }

    /**
     * 같은 조합인지 판단하는 키. 나이와 세운이 해마다 달라지므로 기준 연도도 넣는다.
     * A와 B를 바꿔 넣어도 같은 결과가 나오도록 두 사람 정보를 정렬해서 해싱한다.
     */
    private String cacheKey(CompatRequest req) {
        String a = personKey(req.personA());
        String b = personKey(req.personB());
        String first = a.compareTo(b) <= 0 ? a : b;
        String second = a.compareTo(b) <= 0 ? b : a;
        return support.hash(String.join("|",
                req.compatType().name(), first, second,
                String.valueOf(LocalDate.now().getYear()), PROMPT_VERSION));
    }

    private String personKey(PersonInput person) {
        LocalTime time = person.effectiveTime();
        return String.join(",",
                SajuSupport.trimToNull(person.nickname()) == null ? "" : person.nickname().trim(),
                person.birthDate().toString(),
                time == null ? "unknown" : time.toString(),
                person.gender().name());
    }
}
