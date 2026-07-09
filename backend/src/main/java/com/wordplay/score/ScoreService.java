package com.wordplay.score;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.score.dto.ScoreRow;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 게임 공용 랭킹(리더보드). 게임 키별로 닉네임+점수를 저장하고 상위 N위를 조회한다.
 * ddl-auto=validate 환경을 깨지 않도록 JdbcTemplate + CREATE TABLE IF NOT EXISTS로 자체 관리.
 * (멀티플레이 확장 시에도 이 랭킹 API를 그대로 재사용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreService {

    private final JdbcTemplate jdbc;

    /** 랭킹을 지원하는 게임 키(화이트리스트). 새 게임 추가 시 여기에 등록. */
    private static final Set<String> GAMES = Set.of("snake", "territory");

    @PostConstruct
    void init() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tb_score (
                    id BIGSERIAL PRIMARY KEY,
                    game VARCHAR(20) NOT NULL,
                    nick VARCHAR(16) NOT NULL,
                    score INTEGER NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT now()
                )""");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_score_game ON tb_score(game, score DESC)");
        } catch (Exception e) {
            log.warn("랭킹 테이블 초기화 실패(랭킹 기능만 비활성): {}", e.getMessage());
        }
    }

    public List<ScoreRow> top(String game, int limit) {
        String g = validate(game);
        int lim = Math.max(1, Math.min(50, limit));
        List<ScoreRow> rows = jdbc.query(
                "SELECT nick, score FROM tb_score WHERE game = ? ORDER BY score DESC, created_at ASC LIMIT ?",
                (rs, i) -> new ScoreRow(i + 1, rs.getString("nick"), rs.getInt("score")),
                g, lim);
        return rows;
    }

    /** 점수 제출 후 상위 목록 반환. 내 순위는 컨트롤러에서 계산. */
    @Transactional
    public int submit(String game, String nick, int score) {
        String g = validate(game);
        String n = nick == null ? "" : nick.trim();
        if (n.isEmpty()) n = "익명";
        if (n.length() > 16) n = n.substring(0, 16);
        int s = Math.max(0, Math.min(100_000_000, score));
        jdbc.update("INSERT INTO tb_score(game, nick, score) VALUES (?, ?, ?)", g, n, s);
        // 내 순위(같은 점수면 나보다 높은 점수 개수 + 1)
        Integer higher = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_score WHERE game = ? AND score > ?", Integer.class, g, s);
        return (higher == null ? 0 : higher) + 1;
    }

    private String validate(String game) {
        String g = game == null ? "" : game.trim().toLowerCase();
        if (!GAMES.contains(g)) throw new BusinessException(ErrorCode.INVALID_INPUT, "지원하지 않는 게임입니다");
        return g;
    }
}
