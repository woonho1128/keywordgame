package com.wordplay.tetris;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 테트리스 배틀 우승 횟수 랭킹. 닉네임별 누적 승수를 upsert로 관리한다.
 * ddl-auto=validate 환경을 깨지 않도록 JdbcTemplate + CREATE TABLE IF NOT EXISTS로 자체 관리.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TetrisBattleRankService {

    private final JdbcTemplate jdbc;

    public record WinRow(int rank, String nick, int wins) {}

    @PostConstruct
    void init() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tb_tetris_battle (
                    nick VARCHAR(16) PRIMARY KEY,
                    wins INTEGER NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP NOT NULL DEFAULT now()
                )""");
        } catch (Exception e) {
            log.warn("배틀 랭킹 테이블 초기화 실패(랭킹만 비활성): {}", e.getMessage());
        }
    }

    @Transactional
    public void recordWin(String nick) {
        String n = nick == null ? "" : nick.trim();
        if (n.isEmpty()) return;
        if (n.length() > 16) n = n.substring(0, 16);
        jdbc.update("""
            INSERT INTO tb_tetris_battle(nick, wins, updated_at) VALUES (?, 1, now())
            ON CONFLICT (nick) DO UPDATE SET wins = tb_tetris_battle.wins + 1, updated_at = now()
            """, n);
    }

    public List<WinRow> top(int limit) {
        int lim = Math.max(1, Math.min(50, limit));
        return jdbc.query(
                "SELECT nick, wins FROM tb_tetris_battle ORDER BY wins DESC, updated_at ASC LIMIT ?",
                (rs, i) -> new WinRow(i + 1, rs.getString("nick"), rs.getInt("wins")),
                lim);
    }
}
