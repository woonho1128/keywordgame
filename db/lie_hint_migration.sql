-- Lie Hint migration for an existing WordPlay database.

ALTER TABLE TB_GAME
    ADD COLUMN IF NOT EXISTS game_config JSONB;

ALTER TABLE TB_GAME
    DROP CONSTRAINT IF EXISTS ck_game_type;

ALTER TABLE TB_GAME
    ADD CONSTRAINT ck_game_type
    CHECK (game_type IN ('WORDSIM', 'WORDGUESS', 'LIE_HINT'));

ALTER TABLE TB_GUESS_LOG
    ADD COLUMN IF NOT EXISTS extra_result JSONB;
