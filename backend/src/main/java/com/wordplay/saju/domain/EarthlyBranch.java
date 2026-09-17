package com.wordplay.saju.domain;

/**
 * 지지(地支) 12개. 순서가 육십갑자·월지·시지 계산의 기준이므로 바꾸면 안 된다.
 *
 * mainHiddenStem 은 지장간(支藏干) 중 정기(正氣) — 지지의 십성을 뽑을 때 쓴다.
 */
public enum EarthlyBranch {

    JA("자", "子", Element.WATER, true, "쥐", HeavenlyStem.GYE),
    CHUK("축", "丑", Element.EARTH, false, "소", HeavenlyStem.GI),
    IN("인", "寅", Element.WOOD, true, "호랑이", HeavenlyStem.GAP),
    MYO("묘", "卯", Element.WOOD, false, "토끼", HeavenlyStem.EUL),
    JIN("진", "辰", Element.EARTH, true, "용", HeavenlyStem.MU),
    SA("사", "巳", Element.FIRE, false, "뱀", HeavenlyStem.BYEONG),
    O("오", "午", Element.FIRE, true, "말", HeavenlyStem.JEONG),
    MI("미", "未", Element.EARTH, false, "양", HeavenlyStem.GI),
    SIN("신", "申", Element.METAL, true, "원숭이", HeavenlyStem.GYEONG),
    YU("유", "酉", Element.METAL, false, "닭", HeavenlyStem.SIN),
    SUL("술", "戌", Element.EARTH, true, "개", HeavenlyStem.MU),
    HAE("해", "亥", Element.WATER, false, "돼지", HeavenlyStem.IM);

    private final String korean;
    private final String hanja;
    private final Element element;
    private final boolean yang;
    private final String zodiac;
    private final HeavenlyStem mainHiddenStem;

    EarthlyBranch(String korean, String hanja, Element element, boolean yang, String zodiac,
                  HeavenlyStem mainHiddenStem) {
        this.korean = korean;
        this.hanja = hanja;
        this.element = element;
        this.yang = yang;
        this.zodiac = zodiac;
        this.mainHiddenStem = mainHiddenStem;
    }

    public static EarthlyBranch of(int index) {
        return values()[Math.floorMod(index, 12)];
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

    public boolean isYang() {
        return yang;
    }

    /** 띠 (자=쥐 ... 해=돼지) */
    public String zodiac() {
        return zodiac;
    }

    /** 지장간 정기 — 지지의 십성 판정 기준 */
    public HeavenlyStem mainHiddenStem() {
        return mainHiddenStem;
    }
}
