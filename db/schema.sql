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
    answer_word     VARCHAR(100)  NOT NULL,
    word_length     INTEGER       NOT NULL,
    hint_text       VARCHAR(500),
    creator_nick    VARCHAR(50),
    is_public       BOOLEAN       NOT NULL DEFAULT TRUE,
    play_count      INTEGER       NOT NULL DEFAULT 0,
    solved_count    INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_game_type CHECK (game_type IN ('WORDSIM', 'WORDGUESS'))
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
-- 코멘트
-- ---------------------------------------------------------------------
COMMENT ON TABLE TB_GAME IS '게임 방 - 정답/힌트/모드';
COMMENT ON COLUMN TB_GAME.answer_word IS '정답 단어 (NFC 정규화, 평문 저장)';
COMMENT ON COLUMN TB_PLAY_RECORD.status IS 'IN_PROGRESS | SOLVED | GAVE_UP';
COMMENT ON COLUMN TB_GUESS_LOG.letter_result IS 'WordGuess 자모 비교 결과 (Wordle 표준 H/M/S)';
COMMENT ON TABLE TB_SIMILARITY IS 'WordSim 오프라인 사전 - fastText KR 기반 사전 계산';
