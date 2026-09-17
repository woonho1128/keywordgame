package com.wordplay.saju.domain;

/**
 * 천간(天干) 10개. 순서가 육십갑자 계산의 기준이므로 바꾸면 안 된다.
 */
public enum HeavenlyStem {

    GAP("갑", "甲", Element.WOOD, true),
    EUL("을", "乙", Element.WOOD, false),
    BYEONG("병", "丙", Element.FIRE, true),
    JEONG("정", "丁", Element.FIRE, false),
    MU("무", "戊", Element.EARTH, true),
    GI("기", "己", Element.EARTH, false),
    GYEONG("경", "庚", Element.METAL, true),
    SIN("신", "辛", Element.METAL, false),
    IM("임", "壬", Element.WATER, true),
    GYE("계", "癸", Element.WATER, false);

    private final String korean;
    private final String hanja;
    private final Element element;
    private final boolean yang;

    HeavenlyStem(String korean, String hanja, Element element, boolean yang) {
        this.korean = korean;
        this.hanja = hanja;
        this.element = element;
        this.yang = yang;
    }

    public static HeavenlyStem of(int index) {
        return values()[Math.floorMod(index, 10)];
    }

    public String korean() {
        return korean;
    }

    public String hanja() {
        return hanja;
    }

    public Element element() {
        return element;
    }

    /** 예: "갑(甲)" */
    public String display() {
        return korean + "(" + hanja + ")";
    }

    public boolean isYang() {
        return yang;
    }
}
