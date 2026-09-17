package com.wordplay.saju.entity;

import com.wordplay.saju.domain.Gender;
import com.wordplay.saju.domain.SajuType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 사주 해석 기록. 공유 URL(/saju/{readingId})로 다시 열어볼 수 있도록 저장한다.
 *
 * <p>cacheKey 가 같으면(같은 사람 + 같은 종류) 이미 만든 해석을 재사용한다.
 * 사주는 바뀌지 않으니 매번 새로 뽑을 이유가 없고, AI 호출 비용도 아낀다.
 */
@Entity
@Table(name = "TB_SAJU_READING")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SajuReading {

    @Id
    @Column(name = "reading_id", length = 12)
    private String readingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "saju_type", nullable = false, length = 20)
    private SajuType sajuType;

    @Column(name = "nickname", length = 20)
    private String nickname;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    /** 출생시각 — 모르면 null */
    @Column(name = "birth_time")
    private LocalTime birthTime;

    @Column(name = "time_unknown", nullable = false)
    private Boolean timeUnknown;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false, length = 10)
    private Gender gender;

    /** 계산된 사주팔자 (SajuChart JSON) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chart_json", nullable = false, columnDefinition = "jsonb")
    private String chartJson;

    /** AI 해석 결과 (SajuResult JSON) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", nullable = false, columnDefinition = "jsonb")
    private String resultJson;

    @Column(name = "ai_model", length = 60)
    private String aiModel;

    /** 입력값 해시 — 동일 입력 재사용 판단용 */
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
        if (timeUnknown == null) timeUnknown = Boolean.FALSE;
    }
}
