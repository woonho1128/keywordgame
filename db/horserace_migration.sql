-- =====================================================================
-- 경마(Horse Race) 계정 테이블
-- 배포 시 서버 DB에 1회 실행:  psql ... -f db/horserace_migration.sql
-- =====================================================================

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
