package com.wordplay.saju.domain;

/**
 * 성별 — 대운 순행/역행 판정에 쓰인다 (양남음녀 순행, 음남양녀 역행).
 */
public enum Gender {

    MALE("남성"),
    FEMALE("여성");

    private final String korean;

    Gender(String korean) {
        this.korean = korean;
    }

    public String korean() {
        return korean;
    }
}
