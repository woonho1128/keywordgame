package com.wordplay.feedback;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.feedback.dto.FeedbackRequest;
import com.wordplay.feedback.dto.FeedbackView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FeedbackService {

    private static final Set<String> CATEGORIES = Set.of("BUG", "IDEA", "GAME", "ETC");
    private static final int MAX_MESSAGE = 2000, MIN_MESSAGE = 5;
    /** 도배 방지: 같은 IP에서 1시간에 5건까지. */
    private static final int RATE_LIMIT = 5;
    private static final long RATE_WINDOW_MS = 60 * 60_000L;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final FeedbackRepository repo;
    private final FeedbackMailer mailer;

    private final Map<String, Deque<Long>> recent = new HashMap<>();

    @Transactional
    public void submit(FeedbackRequest req, String ip, String userAgent) {
        String category = req.category() == null ? "ETC" : req.category().toUpperCase();
        if (!CATEGORIES.contains(category)) category = "ETC";

        String message = req.message() == null ? "" : req.message().trim();
        if (message.length() < MIN_MESSAGE) throw bad("내용을 조금 더 자세히 적어주세요");
        if (message.length() > MAX_MESSAGE) message = message.substring(0, MAX_MESSAGE);

        checkRate(ip);

        Feedback f = repo.save(Feedback.builder()
                .category(category)
                .nickname(cut(req.nickname(), 32))
                .contact(cut(req.contact(), 120))
                .message(message)
                .page(cut(req.page(), 120))
                .userAgent(cut(userAgent, 300))
                .mailSent(false)
                .createdAt(Instant.now())
                .build());

        // 메일이 실패해도 접수는 성공이다(내용은 이미 DB에 있음).
        if (mailer.send(f)) f.setMailSent(true);
    }

    private synchronized void checkRate(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> q = recent.computeIfAbsent(ip == null ? "?" : ip, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() > RATE_WINDOW_MS) q.pollFirst();
        if (q.size() >= RATE_LIMIT) throw bad("잠시 후 다시 시도해주세요(1시간에 " + RATE_LIMIT + "건까지)");
        q.addLast(now);
        if (recent.size() > 5000) recent.clear();   // 메모리 방어
    }

    @Transactional(readOnly = true)
    public List<FeedbackView> list() {
        return repo.findTop200ByOrderByIdDesc().stream()
                .map(f -> new FeedbackView(f.getId(), f.getCategory(), f.getNickname(), f.getContact(),
                        f.getMessage(), f.getPage(), f.isMailSent(), FMT.format(f.getCreatedAt())))
                .toList();
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static BusinessException bad(String m) { return new BusinessException(ErrorCode.INVALID_INPUT, m); }
}
