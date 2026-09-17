package com.wordplay.saju.domain;

/**
 * 오행(五行). 상생(生)·상극(剋) 관계를 담는다.
 *
 * 상생: 목 → 화 → 토 → 금 → 수 → 목
 * 상극: 목 → 토 → 수 → 화 → 금 → 목
 */
public enum Element {

    WOOD("목", "木"),
    FIRE("화", "火"),
    EARTH("토", "土"),
    METAL("금", "金"),
    WATER("수", "水");

    private final String korean;
    private final String hanja;

    Element(String korean, String hanja) {
        this.korean = korean;
        this.hanja = hanja;
    }

    public String korean() {
        return korean;
    }

    public String hanja() {
        return hanja;
    }

    /** 내가 생(生)하는 오행 — 목생화, 화생토, 토생금, 금생수, 수생목 */
    public Element generates() {
        return values()[(ordinal() + 1) % 5];
    }

    /** 내가 극(剋)하는 오행 — 목극토, 토극수, 수극화, 화극금, 금극목 */
    public Element controls() {
        return values()[(ordinal() + 2) % 5];
    }
}
