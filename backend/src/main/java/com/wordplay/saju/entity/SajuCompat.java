package com.wordplay.saju.entity;

import com.wordplay.saju.domain.CompatType;
import com.wordplay.saju.domain.Gender;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 궁합 해석 기록. 공유 URL(/saju/compat/{compatId})로 다시 열어볼 수 있다.
 *
 * <p>사주 기록({@link SajuReading})과 같은 원칙: 합·충 판정과 점수는 서버가 계산해
 * analysis_json 에 넣고, AI 해석만 result_json 에 담는다.
 */
@Entity
@Table(name = "TB_SAJU_COMPAT")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SajuCompat {

    @Id
    @Column(name = "compat_id", length = 12)
    private String compatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "compat_type", nullable = false, length = 20)
    private CompatType compatType;

    @Column(name = "a_nickname", length = 20)
    private String aNickname;

    @Column(name = "a_birth_date", nullable = false)
    private LocalDate aBirthDate;

    @Column(name = "a_birth_time")
    private LocalTime aBirthTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "a_gender", nullable = false, length = 10)
    private Gender aGender;

    @Column(name = "b_nickname", length = 20)
    private String bNickname;

    @Column(name = "b_birth_date", nullable = false)
    private LocalDate bBirthDate;

    @Column(name = "b_birth_time")
    private LocalTime bBirthTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "b_gender", nullable = false, length = 10)
    private Gender bGender;

    @Column(name = "score", nullable = false)
    private Integer score;

    /** 서버가 계산한 두 사주 + 관계 근거 (CompatAnalysis JSON) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "analysis_json", nullable = false, columnDefinition = "jsonb")
    private String analysisJson;

    /** AI 해석 결과 (CompatResult JSON) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", nullable = false, columnDefinition = "jsonb")
    private String resultJson;

    @Column(name = "ai_model", length = 60)
    private String aiModel;

    @Column(name = "cache_key", nullable = false, length = 64)
    private String cacheKey;

    @Column(name = "view_count", nullable = false)
    private Integer viewCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (viewCount == null) viewCount = 0;
    }
}
