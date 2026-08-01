package com.wordplay.feedback;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.feedback.dto.FeedbackRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 메일 한도·도배 방지 동작 검증. 메일이 막혀도 접수는 계속 돼야 한다. */
class FeedbackQuotaTest {

    private FeedbackRepository repo;
    private FeedbackMailer mailer;
    private FeedbackService service;
    private final List<Feedback> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = mock(FeedbackRepository.class);
        mailer = mock(FeedbackMailer.class);
        service = new FeedbackService(repo, mailer);
        ReflectionTestUtils.setField(service, "dailyLimit", 80);
        ReflectionTestUtils.setField(service, "monthlyLimit", 2500);

        saved.clear();
        when(repo.save(any(Feedback.class))).thenAnswer(inv -> {
            Feedback f = inv.getArgument(0);
            f.setId((long) (saved.size() + 1));
            saved.add(f);
            return f;
        });
        when(mailer.send(any())).thenReturn(true);
    }

    private FeedbackRequest req() {
        return new FeedbackRequest("BUG", "우노", "a@b.com", "차오차오에서 의심 버튼이 안 눌려요", "/ciao");
    }

    @Test
    void 한도_안이면_메일을_보낸다() {
        when(repo.countByMailSentTrueAndCreatedAtAfter(any(Instant.class))).thenReturn(3L);
        service.submit(req(), "1.1.1.1", "UA");
        verify(mailer).send(any());
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).isMailSent()).isTrue();
    }

    @Test
    void 하루_한도를_넘으면_메일은_건너뛰고_접수는_저장된다() {
        when(repo.countByMailSentTrueAndCreatedAtAfter(any(Instant.class))).thenReturn(80L);
        service.submit(req(), "1.1.1.1", "UA");
        verify(mailer, never()).send(any());
        assertThat(saved).hasSize(1);                       // 내용은 남는다
        assertThat(saved.get(0).isMailSent()).isFalse();
    }

    @Test
    void 도배는_1시간에_5건까지만_받는다() {
        when(repo.countByMailSentTrueAndCreatedAtAfter(any(Instant.class))).thenReturn(0L);
        for (int i = 0; i < 5; i++) service.submit(req(), "9.9.9.9", "UA");
        assertThatThrownBy(() -> service.submit(req(), "9.9.9.9", "UA"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("잠시 후 다시");
        assertThat(saved).hasSize(5);
    }

    @Test
    void 너무_짧은_내용은_거부한다() {
        assertThatThrownBy(() -> service.submit(
                new FeedbackRequest("BUG", null, null, "짧", null), "2.2.2.2", "UA"))
                .isInstanceOf(BusinessException.class);
        assertThat(saved).isEmpty();
    }

    @Test
    void 알_수_없는_분류는_기타로_처리한다() {
        when(repo.countByMailSentTrueAndCreatedAtAfter(any(Instant.class))).thenReturn(0L);
        service.submit(new FeedbackRequest("HACK", null, null, "분류값 검증 테스트입니다", null), "3.3.3.3", "UA");
        assertThat(saved.get(0).getCategory()).isEqualTo("ETC");
    }
}
