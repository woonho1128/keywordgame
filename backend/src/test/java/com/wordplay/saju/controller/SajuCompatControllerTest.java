package com.wordplay.saju.controller;

import com.wordplay.common.util.SessionManager;
import com.wordplay.saju.domain.CompatType;
import com.wordplay.saju.domain.Gender;
import com.wordplay.saju.dto.CompatRequest;
import com.wordplay.saju.dto.CompatResponse;
import com.wordplay.saju.dto.CompatResult;
import com.wordplay.saju.dto.CompatTypeItem;
import com.wordplay.saju.service.SajuCompatService;
import com.wordplay.saju.service.SajuService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SajuCompatController.class)
class SajuCompatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SajuCompatService compatService;

    @MockBean
    private SajuService sajuService;

    @MockBean
    private SessionManager sessionManager;

    private static final String VALID_BODY = """
            {
              "compatType": "LOVE",
              "personA": {
                "nickname": "한운호", "birthDate": "1997-11-28",
                "birthTime": "00:30", "timeUnknown": false, "gender": "MALE"
              },
              "personB": {
                "nickname": "지은", "birthDate": "1998-03-14",
                "birthTime": null, "timeUnknown": true, "gender": "FEMALE"
              }
            }
            """;

    private CompatResponse stub() {
        return new CompatResponse(
                "cmp12345", "/saju/compat/cmp12345", "LOVE", "연인궁합", "💕",
                null,
                new CompatResult("한 줄", "요약", List.of(), List.of("좋은 점"),
                        List.of("조심할 점"), "A가 B에게", "B가 A에게", List.of("키워드"), "조언"),
                Instant.parse("2026-09-17T00:00:00Z")
        );
    }

    @Test
    void 두_사람_정보가_그대로_전달된다() throws Exception {
        given(compatService.createCompat(any(CompatRequest.class), anyString())).willReturn(stub());
        given(sessionManager.getOrCreate(any(), any())).willReturn("session-key");

        mockMvc.perform(post("/api/v1/saju/compat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.compatId").value("cmp12345"))
                .andExpect(jsonPath("$.data.result.aToB").value("A가 B에게"));

        ArgumentCaptor<CompatRequest> captor = ArgumentCaptor.forClass(CompatRequest.class);
        Mockito.verify(compatService).createCompat(captor.capture(), anyString());

        CompatRequest sent = captor.getValue();
        assertThat(sent.compatType()).isEqualTo(CompatType.LOVE);
        assertThat(sent.personA().birthDate()).isEqualTo(LocalDate.of(1997, 11, 28));
        assertThat(sent.personA().effectiveTime()).isEqualTo(LocalTime.of(0, 30));
        assertThat(sent.personA().gender()).isEqualTo(Gender.MALE);
        assertThat(sent.personB().effectiveTime()).isNull();   // 시간 모름
        assertThat(sent.personB().gender()).isEqualTo(Gender.FEMALE);
    }

    @Test
    void 한쪽_생년월일이_없으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/saju/compat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "compatType": "FRIEND",
                                  "personA": { "birthDate": "1997-11-28", "timeUnknown": true, "gender": "MALE" },
                                  "personB": { "timeUnknown": true, "gender": "FEMALE" }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 공유_링크로_다시_조회할_수_있다() throws Exception {
        given(compatService.getCompat("cmp12345")).willReturn(stub());

        mockMvc.perform(get("/api/v1/saju/compat/cmp12345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareUrl").value("/saju/compat/cmp12345"));
    }

    @Test
    void 궁합_종류는_다섯가지() throws Exception {
        given(compatService.listTypes()).willReturn(
                Arrays.stream(CompatType.values()).map(CompatTypeItem::from).toList());
        given(sajuService.isAvailable()).willReturn(true);

        mockMvc.perform(get("/api/v1/saju/compat/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.types.length()").value(5))
                .andExpect(jsonPath("$.data.types[0].code").value("LOVE"));
    }
}
