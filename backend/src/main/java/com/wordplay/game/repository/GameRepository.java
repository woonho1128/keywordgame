package com.wordplay.game.repository;

import com.wordplay.game.entity.Game;
import com.wordplay.game.entity.GameType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameRepository extends JpaRepository<Game, String> {

    Page<Game> findByIsPublicTrueOrderByCreatedAtDesc(Pageable pageable);

    Page<Game> findByIsPublicTrueAndGameTypeOrderByCreatedAtDesc(GameType gameType, Pageable pageable);
}
