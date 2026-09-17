-- AI 사주 기능 migration for an existing WordPlay database.
-- Supabase SQL Editor에서 실행.
-- ---------------------------------------------------------------------
-- TB_SAJU_READING : AI 사주 해석 기록 (공유 URL /saju/{reading_id})
--   chart_json  : 서버가 계산한 사주팔자 (AI가 계산하지 않음)
--   result_json : AI가 생성한 해석
--   cache_key   : 같은 입력이면 이전 해석 재사용 (AI 호출 비용 절감)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS TB_SAJU_READING (
    reading_id      VARCHAR(12)   PRIMARY KEY,
    saju_type       VARCHAR(20)   NOT NULL,
    nickname        VARCHAR(20),
    birth_date      DATE          NOT NULL,
    birth_time      TIME,
    time_unknown    BOOLEAN       NOT NULL DEFAULT FALSE,
    gender          VARCHAR(10)   NOT NULL,
    chart_json      JSONB         NOT NULL,
    result_json     JSONB         NOT NULL,
    ai_model        VARCHAR(60),
    cache_key       VARCHAR(64)   NOT NULL,
    view_count      INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_saju_type CHECK (saju_type IN
        ('TOTAL', 'LOVE', 'WEALTH', 'CAREER', 'STUDY', 'HEALTH', 'YEARLY')),
    CONSTRAINT ck_saju_gender CHECK (gender IN ('MALE', 'FEMALE'))
);

CREATE INDEX IF NOT EXISTS idx_saju_cache
    ON TB_SAJU_READING (cache_key, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_saju_created
    ON TB_SAJU_READING (created_at DESC);

COMMENT ON TABLE TB_SAJU_READING IS 'AI 사주 해석 기록 - 사주팔자는 서버 계산, 해석만 AI';
