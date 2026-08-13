package com.wordplay.quiz;

import com.wordplay.quiz.dto.QuizRankRow;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 시간 배틀 순위 기록. 난이도·제한시간별로 누가 몇 개 맞혔는지 남긴다.
 *
 * <p>{@code ddl-auto=validate} 환경을 깨지 않도록 기존 랭킹(ScoreService)과 같은 방식으로
 * JdbcTemplate + {@code CREATE TABLE IF NOT EXISTS}로 자체 관리한다(수동 DDL 불필요).
 *
 * <p>제한시간을 함께 키로 쓴다 — 5분 동안 40개 맞힌 기록과 1분에 15개 맞힌 기록을 같은
 * 표에 세우면 순위가 무의미하다.
 *
 * <p>DB가 없거나 실패해도 게임은 그대로 돌아간다(순위만 비어 보인다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizRankService {

    private final JdbcTemplate jdbc;
    private volatile boolean ready = false;

    @PostConstruct
    void init() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tb_quiz_rank (
                    id BIGSERIAL PRIMARY KEY,
                    level SMALLINT NOT NULL,
                    limit_sec INTEGER NOT NULL,
                    nick VARCHAR(16) NOT NULL,
                    correct INTEGER NOT NULL,
                    solved INTEGER NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT now()
                )""");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_quiz_rank_board "
                    + "ON tb_quiz_rank(level, limit_sec, correct DESC, created_at ASC)");
            ready = true;
        } catch (Exception e) {
            log.warn("퀴즈 순위 테이블 초기화 실패(순위만 비활성): {}", e.getMessage());
        }
    }

    /** 한 사람의 배틀 결과를 남긴다. 실패해도 조용히 넘어간다. */
    public void record(int level, int limitSec, String nick, int correct, int solved) {
        if (!ready || nick == null || nick.isBlank()) return;
        // 한 문제도 못 맞힌 기록은 순위표를 지저분하게만 만든다.
        if (correct <= 0) return;
        try {
            jdbc.update("INSERT INTO tb_quiz_rank(level, limit_sec, nick, correct, solved) VALUES (?,?,?,?,?)",
                    clampLevel(level), limitSec, nick.length() > 16 ? nick.substring(0, 16) : nick, correct, solved);
        } catch (Exception e) {
            log.warn("퀴즈 순위 기록 실패: {}", e.getMessage());
        }
    }

    /** 난이도·제한시간별 상위 기록. */
    public List<QuizRankRow> top(int level, int limitSec, int limit) {
        if (!ready) return List.of();
        int lim = Math.max(1, Math.min(50, limit));
        try {
            return jdbc.query("""
                    SELECT nick, correct, solved, created_at FROM tb_quiz_rank
                    WHERE level = ? AND limit_sec = ?
                    ORDER BY correct DESC, solved ASC, created_at ASC LIMIT ?""",
                    (rs, i) -> new QuizRankRow(i + 1, rs.getString("nick"), rs.getInt("correct"),
                            rs.getInt("solved"), rs.getTimestamp("created_at").toInstant().toEpochMilli()),
                    clampLevel(level), limitSec, lim);
        } catch (Exception e) {
            log.warn("퀴즈 순위 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    private static int clampLevel(int level) { return Math.max(1, Math.min(10, level)); }
}
