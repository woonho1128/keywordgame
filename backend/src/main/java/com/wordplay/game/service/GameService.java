package com.wordplay.game.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.util.HangulUtil;
import com.wordplay.common.util.NanoIdGenerator;
import com.wordplay.common.util.TextNormalizer;
import com.wordplay.game.dto.CreateGameRequest;
import com.wordplay.game.dto.CreateGameResponse;
import com.wordplay.game.dto.GameResponse;
import com.wordplay.game.dto.LieHintConfig;
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

import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GameService {

    private final GameRepository gameRepository;
    private final SimilarityService similarityService;
    private final ObjectMapper objectMapper;

    @Value("${app.game.id-length:8}")
    private int gameIdLength;

    @Value("${app.game.wordguess-max-attempts:5}")
    private int wordGuessMaxAttempts;

    @Transactional
    public CreateGameResponse createGame(CreateGameRequest req) {
        String answer = TextNormalizer.normalize(req.answerWord());
        if (answer == null || answer.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Enter an answer word");
        }

        if (req.gameType() == GameType.WORDGUESS && !HangulUtil.isAllHangulSyllables(answer)) {
            throw new BusinessException(ErrorCode.INVALID_HANGUL);
        }

        if (req.gameType() == GameType.WORDSIM) {
            if (!similarityService.isAvailable()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "WordSim is unavailable because no dictionary or OpenAI key is configured");
            }
            if (!similarityService.contains(answer)) {
                throw new BusinessException(ErrorCode.WORD_NOT_IN_DICTIONARY,
                        "This word cannot be processed. Try another word");
            }
        }

        String gameConfig = null;
        if (req.gameType() == GameType.LIE_HINT) {
            gameConfig = serializeLieHintConfig(validateLieHintConfig(req));
        }

        Game game = Game.builder()
                .gameId(generateUniqueId())
                .gameType(req.gameType())
                .title(req.title().trim())
                .answerWord(answer)
                .wordLength(answer.length())
                .hintText(req.hintText())
                .gameConfig(gameConfig)
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
        if (g.getGameType() == GameType.LIE_HINT) {
            return GameResponse.fromLieHint(g, parseLieHintConfig(g).hints());
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

    private LieHintConfig validateLieHintConfig(CreateGameRequest req) {
        List<String> rawHints = req.lieHints();
        if (rawHints == null || rawHints.size() != 3) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Lie Hint requires exactly 3 hints");
        }
        if (req.lieIndex() == null || req.lieIndex() < 0 || req.lieIndex() >= rawHints.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Choose one lie hint");
        }

        List<String> hints = rawHints.stream()
                .map(h -> h == null ? "" : h.trim())
                .toList();
        if (hints.stream().anyMatch(h -> h.isEmpty() || h.length() > 120)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Each hint must be 1 to 120 characters");
        }
        if (new HashSet<>(hints).size() != hints.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Hints must not be duplicated");
        }
        return new LieHintConfig(hints, req.lieIndex());
    }

    private String serializeLieHintConfig(LieHintConfig config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to serialize Lie Hint config");
        }
    }

    private LieHintConfig parseLieHintConfig(Game game) {
        try {
            return objectMapper.readValue(game.getGameConfig(), LieHintConfig.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to parse Lie Hint config");
        }
    }

    private String generateUniqueId() {
        for (int i = 0; i < 5; i++) {
            String id = NanoIdGenerator.generate(gameIdLength);
            if (!gameRepository.existsById(id)) return id;
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to generate unique gameId");
    }
}
