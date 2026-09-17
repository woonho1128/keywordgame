package com.wordplay.saju.domain;

/**
 * 기둥(柱) 하나 — 천간 + 지지 조합. 사주는 이 기둥 4개로 이뤄진다.
 */
public record Pillar(HeavenlyStem stem, EarthlyBranch branch) {

    /** 육십갑자 인덱스로 기둥 생성 (0 = 갑자, 59 = 계해) */
    public static Pillar ofSexagenary(int index) {
        int i = Math.floorMod(index, 60);
        return new Pillar(HeavenlyStem.of(i), EarthlyBranch.of(i));
    }

    /** 육십갑자 인덱스 (0 = 갑자) */
    public int sexagenaryIndex() {
        int stemIndex = stem.ordinal();
        int branchIndex = branch.ordinal();
        // 천간 10 / 지지 12 의 최소공배수 60 안에서 두 인덱스를 동시에 만족하는 값
        for (int i = 0; i < 60; i++) {
            if (i % 10 == stemIndex && i % 12 == branchIndex) return i;
        }
        throw new IllegalStateException("Invalid pillar: " + stem + branch);
    }

    /** 예: "갑자" */
    public String korean() {
        return stem.korean() + branch.korean();
    }

    /** 예: "甲子" */
    public String hanja() {
        return stem.hanja() + branch.hanja();
    }

    /** 예: "갑자(甲子)" */
    public String display() {
        return korean() + "(" + hanja() + ")";
    }
}
