package com.wordplay.saju.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 천간(天干)끼리의 관계.
 *
 * <ul>
 *   <li><b>합(合)</b> — 갑기합토, 을경합금, 병신합수, 정임합목, 무계합화.
 *       인덱스 차가 정확히 5인 짝이다. 서로 끌리는 관계로 본다</li>
 *   <li><b>충(沖)</b> — 갑경, 을신, 병임, 정계. 오행이 상극이면서 음양이 같다.
 *       무·기(중앙 토)는 충하지 않는다</li>
 * </ul>
 */
public enum StemRelation {

    HAP("합", true, "서로 끌어당기는 관계"),
    CHUNG("충", false, "부딪치며 긴장이 생기는 관계");

    private final String korean;
    private final boolean positive;
    private final String meaning;

    StemRelation(String korean, boolean positive, String meaning) {
        this.korean = korean;
        this.positive = positive;
        this.meaning = meaning;
    }

    public String korean() {
        return korean;
    }

    public boolean isPositive() {
        return positive;
    }

    public String meaning() {
        return meaning;
    }

    /** 천간합의 결과 오행 (갑기합토 …). 합이 아니면 null */
    public static Element hapElement(HeavenlyStem a, HeavenlyStem b) {
        if (!isHap(a, b)) return null;
        // 합을 이루는 짝 중 작은 인덱스로 판정: 갑기=토, 을경=금, 병신=수, 정임=목, 무계=화
        int min = Math.min(a.ordinal(), b.ordinal());
        return switch (min) {
            case 0 -> Element.EARTH;
            case 1 -> Element.METAL;
            case 2 -> Element.WATER;
            case 3 -> Element.WOOD;
            default -> Element.FIRE;
        };
    }

    public static boolean isHap(HeavenlyStem a, HeavenlyStem b) {
        return Math.abs(a.ordinal() - b.ordinal()) == 5;
    }

    public static boolean isChung(HeavenlyStem a, HeavenlyStem b) {
        int lo = Math.min(a.ordinal(), b.ordinal());
        int hi = Math.max(a.ordinal(), b.ordinal());
        // 갑경(0,6) 을신(1,7) 병임(2,8) 정계(3,9)
        return lo <= 3 && hi - lo == 6;
    }

    public static List<StemRelation> between(HeavenlyStem a, HeavenlyStem b) {
        List<StemRelation> found = new ArrayList<>(1);
        if (isHap(a, b)) found.add(HAP);
        if (isChung(a, b)) found.add(CHUNG);
        return found;
    }
}
