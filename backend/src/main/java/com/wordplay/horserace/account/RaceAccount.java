package com.wordplay.horserace.account;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/** 경마 영속 계정(가상 칩 지갑). 놀이용이며 실제 환전은 없다. */
@Entity
@Table(name = "TB_RACE_ACCOUNT")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RaceAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "nickname", nullable = false, unique = true, length = 16)
    private String nickname;

    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Column(name = "peak_balance", nullable = false)
    private long peakBalance;

    @Column(name = "total_races", nullable = false)
    private int totalRaces;

    @Column(name = "wins", nullable = false)
    private int wins;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "last_bonus_date")
    private LocalDate lastBonusDate;

    @Column(name = "bonus_count_today", nullable = false)
    private int bonusCountToday;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
