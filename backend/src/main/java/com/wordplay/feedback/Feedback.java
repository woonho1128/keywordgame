package com.wordplay.feedback;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** 사용자 건의/문의. 메일 발송이 실패해도 기록은 남도록 먼저 저장한다. */
@Entity
@Table(name = "TB_FEEDBACK")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** BUG(버그) / IDEA(건의) / GAME(새 게임) / ETC(기타) */
    @Column(name = "category", nullable = false, length = 16)
    private String category;

    @Column(name = "nickname", length = 32)
    private String nickname;

    /** 답장받을 연락처(선택). */
    @Column(name = "contact", length = 120)
    private String contact;

    @Column(name = "message", nullable = false, length = 2000)
    private String message;

    /** 어느 화면에서 보냈는지(예: /ciao). */
    @Column(name = "page", length = 120)
    private String page;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    /** 메일 발송 성공 여부. 실패해도 접수는 성공 처리한다. */
    @Column(name = "mail_sent", nullable = false)
    private boolean mailSent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
