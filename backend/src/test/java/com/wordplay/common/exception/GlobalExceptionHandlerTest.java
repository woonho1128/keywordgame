package com.wordplay.common.exception;

import com.wordplay.common.util.SessionManager;
import com.wordplay.game.controller.GameController;
import com.wordplay.game.service.GameService;
import com.wordplay.leaderboard.controller.LeaderboardController;
import com.wordplay.leaderboard.service.LeaderboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 클라이언트 요청 오류가 500이 아니라 400/404/405로 응답되는지 확인. */
@WebMvcTest(
        controllers = {GameController.class, LeaderboardController.class},
        properties = "app.cors.allowed-origins=http://localhost:3000"
)
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mvc;

    @MockBean GameService gameService;
    @MockBean LeaderboardService leaderboardService;
    @MockBean SessionManager sessionManager;

    @Test
    void 깨진_JSON은_400() throws Exception {
        mvc.perform(post("/api/v1/games").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 잘못된_enum_값은_400() throws Exception {
        mvc.perform(post("/api/v1/games").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameType\":\"FOO\",\"title\":\"t\",\"answerWord\":\"사과\",\"creatorNick\":\"a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 숫자가_아닌_파라미터는_400() throws Exception {
        mvc.perform(get("/api/v1/games/G1/leaderboard").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 없는_경로는_404() throws Exception {
        mvc.perform(get("/api/v1/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void 지원하지_않는_메서드는_405() throws Exception {
        mvc.perform(put("/api/v1/games"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }
}
