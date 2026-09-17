package com.wordplay.saju.domain;

import java.util.List;

/**
 * 지지(地支) 12개. 순서가 육십갑자·월지·시지 계산의 기준이므로 바꾸면 안 된다.
 *
 * <p>hiddenStems 는 지장간(支藏干) — 지지 속에 숨은 천간을 여기(餘氣)·중기(中氣)·정기(正氣)
 * 순으로 담는다. 마지막이 정기이고, 십성을 뽑을 땐 정기를 쓴다.
 */
public enum EarthlyBranch {

    JA("자", "子", Element.WATER, true, "쥐",
            List.of(HeavenlyStem.IM, HeavenlyStem.GYE)),
    CHUK("축", "丑", Element.EARTH, false, "소",
            List.of(HeavenlyStem.GYE, HeavenlyStem.SIN, HeavenlyStem.GI)),
    IN("인", "寅", Element.WOOD, true, "호랑이",
            List.of(HeavenlyStem.MU, HeavenlyStem.BYEONG, HeavenlyStem.GAP)),
    MYO("묘", "卯", Element.WOOD, false, "토끼",
            List.of(HeavenlyStem.GAP, HeavenlyStem.EUL)),
    JIN("진", "辰", Element.EARTH, true, "용",
            List.of(HeavenlyStem.EUL, HeavenlyStem.GYE, HeavenlyStem.MU)),
    SA("사", "巳", Element.FIRE, false, "뱀",
            List.of(HeavenlyStem.MU, HeavenlyStem.GYEONG, HeavenlyStem.BYEONG)),
    O("오", "午", Element.FIRE, true, "말",
            List.of(HeavenlyStem.BYEONG, HeavenlyStem.GI, HeavenlyStem.JEONG)),
    MI("미", "未", Element.EARTH, false, "양",
            List.of(HeavenlyStem.JEONG, HeavenlyStem.EUL, HeavenlyStem.GI)),
    SIN("신", "申", Element.METAL, true, "원숭이",
            List.of(HeavenlyStem.MU, HeavenlyStem.IM, HeavenlyStem.GYEONG)),
    YU("유", "酉", Element.METAL, false, "닭",
            List.of(HeavenlyStem.GYEONG, HeavenlyStem.SIN)),
    SUL("술", "戌", Element.EARTH, true, "개",
            List.of(HeavenlyStem.SIN, HeavenlyStem.JEONG, HeavenlyStem.MU)),
    HAE("해", "亥", Element.WATER, false, "돼지",
            List.of(HeavenlyStem.MU, HeavenlyStem.GAP, HeavenlyStem.IM));

    private final String korean;
    private final String hanja;
    private final Element element;
    private final boolean yang;
    private final String zodiac;
    private final List<HeavenlyStem> hiddenStems;

    EarthlyBranch(String korean, String hanja, Element element, boolean yang, String zodiac,
                  List<HeavenlyStem> hiddenStems) {
        this.korean = korean;
        this.hanja = hanja;
        this.element = element;
        this.yang = yang;
        this.zodiac = zodiac;
        this.hiddenStems = hiddenStems;
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

    /** 예: "자(子)" */
    public String display() {
        return korean + "(" + hanja + ")";
    }

    /** 지장간 전체 — 여기·중기·정기 순 */
    public List<HeavenlyStem> hiddenStems() {
        return hiddenStems;
    }

    /** 지장간 정기 — 지지의 십성 판정 기준 */
    public HeavenlyStem mainHiddenStem() {
        return hiddenStems.get(hiddenStems.size() - 1);
    }
}
