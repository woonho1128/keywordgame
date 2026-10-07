-- WordGuess 동시 요청 수정용 마이그레이션 (기존 WordPlay DB 대상).
-- 설계: WORDGUESS_FIX_DESIGN.md 3장
--
-- 더블클릭/동시 요청으로 같은 기록 안에 guess_order가 겹친 로그가 있을 수 있다.
-- 겹친 기록만 시도 번호를 다시 매기고 attempt_count를 실제 로그 수로 맞춘 뒤 유니크 제약을 건다.
-- (attempt_count를 맞추지 않으면 다음 추측 번호가 기존 번호와 겹쳐 제약 위반이 난다)

-- 0) 실행 전 확인: 겹친 기록과 실제 로그 수 (결과가 0행이면 1~2단계는 아무것도 바꾸지 않는다)
SELECT r.record_id, r.game_id, r.player_nick, r.status, r.attempt_count,
       (SELECT COUNT(*) FROM TB_GUESS_LOG l WHERE l.record_id = r.record_id) AS log_rows
FROM TB_PLAY_RECORD r
WHERE r.record_id IN (
    SELECT record_id FROM TB_GUESS_LOG GROUP BY record_id, guess_order HAVING COUNT(*) > 1
);

BEGIN;

CREATE TEMP TABLE tmp_dup_records ON COMMIT DROP AS
SELECT DISTINCT record_id
FROM TB_GUESS_LOG
GROUP BY record_id, guess_order
HAVING COUNT(*) > 1;

-- 1) 겹친 기록의 시도 번호를 (기존 번호, log_id) 순서로 1부터 다시 매김
UPDATE TB_GUESS_LOG l
SET guess_order = s.rn
FROM (
    SELECT log_id,
           ROW_NUMBER() OVER (PARTITION BY record_id ORDER BY guess_order, log_id) AS rn
    FROM TB_GUESS_LOG
    WHERE record_id IN (SELECT record_id FROM tmp_dup_records)
) s
WHERE l.log_id = s.log_id
  AND l.guess_order <> s.rn;

-- 2) 같은 기록의 attempt_count를 실제 로그 수로 보정
UPDATE TB_PLAY_RECORD r
SET attempt_count = (SELECT COUNT(*) FROM TB_GUESS_LOG l WHERE l.record_id = r.record_id)
WHERE r.record_id IN (SELECT record_id FROM tmp_dup_records);

-- 3) 재발 방지 제약
ALTER TABLE TB_GUESS_LOG DROP CONSTRAINT IF EXISTS uk_log_record_order;
ALTER TABLE TB_GUESS_LOG
    ADD CONSTRAINT uk_log_record_order UNIQUE (record_id, guess_order);

COMMIT;
