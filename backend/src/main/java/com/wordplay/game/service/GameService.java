package com.wordplay.game.service;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.util.HangulUtil;
import com.wordplay.common.util.NanoIdGenerator;
import com.wordplay.common.util.TextNormalizer;
import com.wordplay.game.dto.CreateGameRequest;
import com.wordplay.game.dto.CreateGameResponse;
import com.wordplay.game.dto.GameResponse;
import com.wordplay.game.dto.RecentGameItem;
import com.wordplay.game.entity.Game;
import com.wordplay.game.entity.GameType;
import com.wordplay.game.repository.GameRepository;
import com.wordplay.similarity.SimilarityService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GameService {

    private final GameRepository gameRepository;
    private final SimilarityService similarityService;

    @Value("${app.game.id-length:8}")
    private int gameIdLength;

    @Value("${app.game.wordguess-max-attempts:5}")
    private int wordGuessMaxAttempts;

    @Transactional
    public CreateGameResponse createGame(CreateGameRequest req) {
        String answer = TextNormalizer.normalize(req.answerWord());
        if (answer == null || answer.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "정답 단어를 입력해주세요");
        }

        // WordGuess: 모든 글자가 한글 음절인지 검증
        if (req.gameType() == GameType.WORDGUESS && !HangulUtil.isAllHangulSyllables(answer)) {
            throw new BusinessException(ErrorCode.INVALID_HANGUL);
        }

        // WordSim: 정답에 대해 벡터 확보 가능한지 확인
        // (사전 또는 OpenAI 둘 중 하나에서)
        if (req.gameType() == GameType.WORDSIM) {
            if (!similarityService.isAvailable()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "WordSim 사용 불가 — 사전/OpenAI 키 모두 미설정");
            }
            if (!similarityService.contains(answer)) {
                throw new BusinessException(ErrorCode.WORD_NOT_IN_DICTIONARY,
                        "처리할 수 없는 단어입니다. 다른 단어를 시도해주세요");
            }
        }

        Game game = Game.builder()
                .gameId(generateUniqueId())
                .gameType(req.gameType())
                .title(req.title().trim())
                .answerWord(answer)
                .wordLength(answer.length())
                .hintText(req.hintText())
                .creatorNick(req.creatorNick().trim())
                .isPublic(req.isPublic() == null ? Boolean.TRUE : req.isPublic())
                .build();

        gameRepository.save(game);

        return new CreateGameResponse(
                game.getGameId(),
                "/g/" + game.getGameId(),
                game.getTitle(),
                game.getCreatorNick(),
                game.getGameType(),
                game.getWordLength()
        );
    }

    @Transactional(readOnly = true)
    public GameResponse getGame(String gameId) {
        Game g = gameRepository.findById(gameId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));
        if (g.getGameType() == GameType.WORDSIM && similarityService.isAvailable()) {
            var refs = similarityService.getReferenceScores(g.getAnswerWord());
            return GameResponse.fromWithSim(g, refs);
        }
        return GameResponse.from(g, wordGuessMaxAttempts);
    }

    @Transactional(readOnly = true)
    public Page<RecentGameItem> getRecentGames(GameType type, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<Game> games = (type == null)
                ? gameRepository.findByIsPublicTrueOrderByCreatedAtDesc(pageable)
                : gameRepository.findByIsPublicTrueAndGameTypeOrderByCreatedAtDesc(type, pageable);
        return games.map(RecentGameItem::from);
    }

    private String generateUniqueId() {
        for (int i = 0; i < 5; i++) {
            String id = NanoIdGenerator.generate(gameIdLength);
            if (!gameRepository.existsById(id)) return id;
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to generate unique gameId");
    }
}
