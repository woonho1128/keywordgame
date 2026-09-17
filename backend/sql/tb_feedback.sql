-- 건의사항 테이블. ddl-auto: validate 라서 배포 전에 한 번 실행해야 한다.
CREATE TABLE IF NOT EXISTS TB_FEEDBACK (
    id          BIGSERIAL PRIMARY KEY,
    category    VARCHAR(16)  NOT NULL,
    nickname    VARCHAR(32),
    contact     VARCHAR(120),
    message     VARCHAR(2000) NOT NULL,
    page        VARCHAR(120),
    user_agent  VARCHAR(300),
    mail_sent   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_feedback_created_at ON TB_FEEDBACK (created_at DESC);
