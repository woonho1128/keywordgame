-- AI 사주 + 궁합 migration for an existing WordPlay database.
-- Supabase SQL Editor에서 실행. CREATE TABLE IF NOT EXISTS 라서 이미 만든 테이블이
-- 있어도 안전하게 다시 실행할 수 있다 (사주만 적용해둔 DB는 궁합 테이블만 새로 생긴다).
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

-- ---------------------------------------------------------------------
-- TB_SAJU_COMPAT : AI 궁합 해석 기록 (공유 URL /saju/compat/{compat_id})
--   analysis_json : 서버가 계산한 두 사주 + 합/충 관계 + 점수
--   result_json   : AI가 생성한 해석
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS TB_SAJU_COMPAT (
    compat_id       VARCHAR(12)   PRIMARY KEY,
    compat_type     VARCHAR(20)   NOT NULL,
    a_nickname      VARCHAR(20),
    a_birth_date    DATE          NOT NULL,
    a_birth_time    TIME,
    a_gender        VARCHAR(10)   NOT NULL,
    b_nickname      VARCHAR(20),
    b_birth_date    DATE          NOT NULL,
    b_birth_time    TIME,
    b_gender        VARCHAR(10)   NOT NULL,
    score           INTEGER       NOT NULL,
    analysis_json   JSONB         NOT NULL,
    result_json     JSONB         NOT NULL,
    ai_model        VARCHAR(60),
    cache_key       VARCHAR(64)   NOT NULL,
    view_count      INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_compat_type CHECK (compat_type IN
        ('LOVE', 'COUPLE', 'FRIEND', 'WORK', 'FAMILY')),
    CONSTRAINT ck_compat_gender CHECK (a_gender IN ('MALE', 'FEMALE') AND b_gender IN ('MALE', 'FEMALE'))
);

CREATE INDEX IF NOT EXISTS idx_compat_cache
    ON TB_SAJU_COMPAT (cache_key, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_compat_created
    ON TB_SAJU_COMPAT (created_at DESC);

COMMENT ON TABLE TB_SAJU_COMPAT IS 'AI 궁합 해석 기록 - 합/충 판정과 점수는 서버 계산, 해석만 AI';
COMMENT ON COLUMN TB_SAJU_COMPAT.analysis_json IS '두 사주 + 자리별 합/충 근거 + 점수';

-- ---------------------------------------------------------------------
-- 사주 종류 CHECK 제약 제거
--   사주 종류는 앞으로도 늘어난다(미래인연 등). CHECK를 두면 종류를 추가할 때마다
--   DB 마이그레이션을 해야 하고, 빠뜨리면 운영에서 INSERT가 깨진다.
--   종류는 Java enum(@Enumerated STRING)과 API 입력 검증에서 이미 막고 있으므로
--   DB 제약은 걷어낸다.
-- ---------------------------------------------------------------------
ALTER TABLE TB_SAJU_READING DROP CONSTRAINT IF EXISTS ck_saju_type;
