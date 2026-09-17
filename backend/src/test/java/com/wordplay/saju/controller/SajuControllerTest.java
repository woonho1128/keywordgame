package com.wordplay.saju.controller;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.util.SessionManager;
import com.wordplay.saju.domain.Gender;
import com.wordplay.saju.domain.SajuType;
import com.wordplay.saju.dto.SajuReadingResponse;
import com.wordplay.saju.dto.SajuRequest;
import com.wordplay.saju.dto.SajuResult;
import com.wordplay.saju.dto.SajuTypeItem;
import com.wordplay.saju.service.SajuService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 컨트롤러 슬라이스 테스트 — 요청 바인딩(특히 날짜/시각)과 응답 직렬화를 확인한다.
 */
@WebMvcTest(SajuController.class)
class SajuControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SajuService sajuService;

    @MockBean
    private SessionManager sessionManager;

    private static final String VALID_BODY = """
            {
              "sajuType": "LOVE",
              "nickname": "우노",
              "birthDate": "1990-05-15",
              "birthTime": "10:30",
              "timeUnknown": false,
              "gender": "MALE"
            }
            """;

    private SajuReadingResponse stubResponse() {
        return new SajuReadingResponse(
                "abc12345", "/saju/abc12345", "LOVE", "연애사주", "💗",
                "우노", LocalDate.of(1990, 5, 15), "10:30", "남성",
                null,
                new SajuResult("한 줄 총평", "요약",
                        List.of(new SajuResult.Highlight("앞으로 만날 인연", "3번", "재성이 셋이라")),
                        List.of(new SajuResult.Section("제목", "본문")),
                        List.of("강점"), List.of("주의점"),
                        List.of(new SajuResult.Period("30~39세 무신 대운", "흐름")),
                        List.of(new SajuResult.Encounter("2027년 정미년", false, "일터·업무 모임",
                                "프로젝트로 붙어 일하다",
                                new SajuResult.Partner("또래", "기획·마케팅 쪽", "조용한데 할 말은 하는"),
                                "세운 천간이 재성")),
                        List.of("키워드"), null, "조언", 77),
                Instant.parse("2026-09-17T00:00:00Z")
        );
    }

    @Test
    void 사주_생성_요청이_날짜와_시각까지_그대로_전달된다() throws Exception {
        given(sajuService.createReading(any(SajuRequest.class), anyString())).willReturn(stubResponse());
        given(sessionManager.getOrCreate(any(), any())).willReturn("session-key");

        mockMvc.perform(post("/api/v1/saju")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.readingId").value("abc12345"))
                .andExpect(jsonPath("$.data.result.score").value(77))
                .andExpect(jsonPath("$.data.result.highlights[0].value").value("3번"));

        ArgumentCaptor<SajuRequest> captor = ArgumentCaptor.forClass(SajuRequest.class);
        org.mockito.Mockito.verify(sajuService).createReading(captor.capture(), anyString());

        SajuRequest sent = captor.getValue();
        assertThat(sent.sajuType()).isEqualTo(SajuType.LOVE);
        assertThat(sent.birthDate()).isEqualTo(LocalDate.of(1990, 5, 15));
        assertThat(sent.birthTime()).isEqualTo(LocalTime.of(10, 30));
        assertThat(sent.gender()).isEqualTo(Gender.MALE);
        assertThat(sent.effectiveTime()).isEqualTo(LocalTime.of(10, 30));
    }

    @Test
    void 시간_모름이면_시주를_빼고_계산하도록_전달된다() throws Exception {
        given(sajuService.createReading(any(SajuRequest.class), anyString())).willReturn(stubResponse());
        given(sessionManager.getOrCreate(any(), any())).willReturn("session-key");

        mockMvc.perform(post("/api/v1/saju")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sajuType": "TOTAL",
                                  "birthDate": "1990-05-15",
                                  "birthTime": null,
                                  "timeUnknown": true,
                                  "gender": "FEMALE"
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<SajuRequest> captor = ArgumentCaptor.forClass(SajuRequest.class);
        org.mockito.Mockito.verify(sajuService).createReading(captor.capture(), anyString());
        assertThat(captor.getValue().effectiveTime()).isNull();
    }

    @Test
    void 생년월일이_없으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/saju")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "sajuType": "TOTAL", "timeUnknown": true, "gender": "MALE" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void AI_키가_없으면_503으로_알려준다() throws Exception {
        given(sessionManager.getOrCreate(any(), any())).willReturn("session-key");
        willThrow(new BusinessException(ErrorCode.SAJU_AI_UNAVAILABLE))
                .given(sajuService).createReading(any(SajuRequest.class), anyString());

        mockMvc.perform(post("/api/v1/saju")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SAJU_AI_UNAVAILABLE"));
    }

    @Test
    void 공유_링크로_다시_조회할_수_있다() throws Exception {
        given(sajuService.getReading("abc12345")).willReturn(stubResponse());

        mockMvc.perform(get("/api/v1/saju/abc12345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareUrl").value("/saju/abc12345"))
                .andExpect(jsonPath("$.data.birthTimeLabel").value("10:30"));
    }

    @Test
    void 사주_종류_목록과_사용가능_여부를_준다() throws Exception {
        given(sajuService.listTypes()).willReturn(
                Arrays.stream(SajuType.values()).map(SajuTypeItem::from).toList());
        given(sajuService.isAvailable()).willReturn(true);

        mockMvc.perform(get("/api/v1/saju/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.types.length()").value(SajuType.values().length))
                .andExpect(jsonPath("$.data.types[0].code").value("TOTAL"));
    }
}
