-- =====================================================================
-- WordPlay Schema v1.1
-- PostgreSQL (Supabase Free Tier)
-- =====================================================================

-- ---------------------------------------------------------------------
-- TB_GAME : 게임 방
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS TB_GAME (
    game_id         VARCHAR(12)   PRIMARY KEY,
    game_type       VARCHAR(20)   NOT NULL,
    title           VARCHAR(60),
    answer_word     VARCHAR(100)  NOT NULL,
    word_length     INTEGER       NOT NULL,
    hint_text       VARCHAR(500),
    game_config     JSONB,
    creator_nick    VARCHAR(50),
    is_public       BOOLEAN       NOT NULL DEFAULT TRUE,
    play_count      INTEGER       NOT NULL DEFAULT 0,
    solved_count    INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_game_type CHECK (game_type IN ('WORDSIM', 'WORDGUESS', 'LIE_HINT'))
);

CREATE INDEX IF NOT EXISTS idx_game_public_created
    ON TB_GAME (is_public, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_game_type_created
    ON TB_GAME (game_type, created_at DESC);


-- ---------------------------------------------------------------------
-- TB_PLAY_RECORD : 플레이 기록
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS TB_PLAY_RECORD (
    record_id       BIGSERIAL     PRIMARY KEY,
    game_id         VARCHAR(12)   NOT NULL,
    player_nick     VARCHAR(50)   NOT NULL,
    session_key     VARCHAR(64)   NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'IN_PROGRESS',
    attempt_count   INTEGER       NOT NULL DEFAULT 0,
    time_spent_sec  INTEGER,
    started_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    finished_at     TIMESTAMP,

    CONSTRAINT fk_play_game FOREIGN KEY (game_id)
        REFERENCES TB_GAME(game_id) ON DELETE CASCADE,
    CONSTRAINT uk_play_session UNIQUE (game_id, session_key),
    CONSTRAINT ck_play_status CHECK (status IN ('IN_PROGRESS', 'SOLVED', 'GAVE_UP'))
);

-- 리더보드 partial index (정답자만)
CREATE INDEX IF NOT EXISTS idx_play_leaderboard
    ON TB_PLAY_RECORD (game_id, attempt_count ASC, time_spent_sec ASC)
    WHERE status = 'SOLVED';


-- ---------------------------------------------------------------------
-- TB_GUESS_LOG : 시도 로그
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS TB_GUESS_LOG (
    log_id          BIGSERIAL     PRIMARY KEY,
    record_id       BIGINT        NOT NULL,
    guess_word      VARCHAR(100)  NOT NULL,
    guess_order     INTEGER       NOT NULL,
    similarity      REAL,
    rank_value      INTEGER,
    letter_result   JSONB,
    extra_result    JSONB,
    is_correct      BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_log_record FOREIGN KEY (record_id)
        REFERENCES TB_PLAY_RECORD(record_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_log_record
    ON TB_GUESS_LOG (record_id, guess_order);


-- ---------------------------------------------------------------------
-- TB_SIMILARITY : WordSim 오프라인 사전 (정답별 top-1000)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS TB_SIMILARITY (
    word_a          VARCHAR(50)   NOT NULL,
    word_b          VARCHAR(50)   NOT NULL,
    similarity      REAL          NOT NULL,
    rank_value      INTEGER       NOT NULL,

    PRIMARY KEY (word_a, word_b)
);

CREATE INDEX IF NOT EXISTS idx_sim_lookup
    ON TB_SIMILARITY (word_a, similarity DESC);



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

    -- 사주 종류는 계속 늘어나므로 CHECK를 걸지 않는다 (Java enum에서 검증)
    CONSTRAINT ck_saju_gender CHECK (gender IN ('MALE', 'FEMALE'))
);

CREATE INDEX IF NOT EXISTS idx_saju_cache
    ON TB_SAJU_READING (cache_key, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_saju_created
    ON TB_SAJU_READING (created_at DESC);

-- ---------------------------------------------------------------------
-- 코멘트
-- ---------------------------------------------------------------------
COMMENT ON TABLE TB_GAME IS '게임 방 - 정답/힌트/모드';
COMMENT ON COLUMN TB_GAME.title IS '게임 제목 (구분용, 신규 게임은 필수)';
COMMENT ON COLUMN TB_GAME.answer_word IS '정답 단어 (NFC 정규화, 평문 저장)';
COMMENT ON COLUMN TB_PLAY_RECORD.status IS 'IN_PROGRESS | SOLVED | GAVE_UP';
COMMENT ON COLUMN TB_GUESS_LOG.letter_result IS 'WordGuess 자모 비교 결과 (Wordle 표준 H/M/S)';
COMMENT ON TABLE TB_SIMILARITY IS 'WordSim 오프라인 사전 - fastText KR 기반 사전 계산';
COMMENT ON TABLE TB_SAJU_READING IS 'AI 사주 해석 기록 - 사주팔자는 서버 계산, 해석만 AI';
COMMENT ON COLUMN TB_SAJU_READING.chart_json IS '서버가 계산한 사주팔자/대운/세운';
COMMENT ON COLUMN TB_SAJU_READING.cache_key IS '입력값 SHA-256 - 동일 입력 재사용 판단';


-- ---------------------------------------------------------------------
-- TB_RACE_ACCOUNT : 경마 영속 계정(가상 칩 지갑, 놀이용·환전 없음)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS TB_RACE_ACCOUNT (
    id                 BIGSERIAL     PRIMARY KEY,
    nickname           VARCHAR(16)   NOT NULL UNIQUE,
    password_hash      VARCHAR(200)  NOT NULL,
    balance            BIGINT        NOT NULL DEFAULT 0,
    peak_balance       BIGINT        NOT NULL DEFAULT 0,
    total_races        INTEGER       NOT NULL DEFAULT 0,
    wins               INTEGER       NOT NULL DEFAULT 0,
    last_login_at      TIMESTAMP,
    last_bonus_date    DATE,
    bonus_count_today  INTEGER       NOT NULL DEFAULT 0,
    created_at         TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS ix_race_account_balance ON TB_RACE_ACCOUNT (balance DESC);
COMMENT ON TABLE TB_RACE_ACCOUNT IS '경마 계정 - 닉+암호(PBKDF2) 영속 가상 칩 지갑';

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
