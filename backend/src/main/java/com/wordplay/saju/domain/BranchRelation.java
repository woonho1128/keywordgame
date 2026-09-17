package com.wordplay.saju.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 지지(地支)끼리의 관계 — 궁합에서 가장 많이 보는 부분이다.
 *
 * <p>특히 <b>일지(日支)</b>는 배우자 자리라 연인·부부 궁합의 핵심으로 본다.
 *
 * <ul>
 *   <li><b>육합(六合)</b> 자축·인해·묘술·진유·사신·오미 — 두 인덱스 합이 12로 나눠 1</li>
 *   <li><b>삼합(三合)</b> 신자진(수)·해묘미(목)·인오술(화)·사유축(금) — 인덱스를 4로 나눈 나머지가 같다.
 *       두 글자만 있으면 반합(半合)</li>
 *   <li><b>충(沖)</b> 자오·축미·인신·묘유·진술·사해 — 인덱스 차 6</li>
 *   <li><b>형(刑)</b> 인사신·축술미 삼형, 자묘 상형, 진진·오오·유유·해해 자형</li>
 *   <li><b>해(害)</b> 자미·축오·인사·묘진·신해·유술 — 두 인덱스 합이 12로 나눠 7</li>
 *   <li><b>파(破)</b> 자유·축진·인해·묘오·사신·미술</li>
 * </ul>
 *
 * <p>한 쌍이 여러 관계에 동시에 걸릴 수 있다 (예: 인해는 육합이면서 파).
 */
public enum BranchRelation {

    YUKHAP("육합", true, "둘이 붙으면 편안해지는 조합"),
    SAMHAP("삼합", true, "목표를 같이 밀고 나가는 조합"),
    CHUNG("충", false, "부딪치고 흔들리는 조합"),
    HYEONG("형", false, "가까울수록 서로를 긁는 조합"),
    HAE("해", false, "사소한 어긋남이 쌓이는 조합"),
    PA("파", false, "한 번씩 깨졌다 붙는 조합");

    private final String korean;
    private final boolean positive;
    private final String meaning;

    BranchRelation(String korean, boolean positive, String meaning) {
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

    /** 자유·축진·인해·묘오·사신·미술 — 공식이 없어 그대로 나열한다 */
    private static final Set<Integer> PA_PAIRS = Set.of(
            key(0, 9), key(1, 4), key(2, 11), key(3, 6), key(5, 8), key(7, 10)
    );

    /** 인사신·축술미 삼형 + 자묘 상형 */
    private static final Set<Integer> HYEONG_PAIRS = Set.of(
            key(2, 5), key(5, 8), key(2, 8),      // 인사신
            key(1, 10), key(10, 7), key(1, 7),    // 축술미
            key(0, 3)                             // 자묘
    );

    /** 진·오·유·해는 같은 글자끼리 만나면 자형(自刑) */
    private static final Set<Integer> SELF_HYEONG = Set.of(4, 6, 9, 11);

    private static int key(int a, int b) {
        return Math.min(a, b) * 12 + Math.max(a, b);
    }

    public static boolean isYukhap(EarthlyBranch a, EarthlyBranch b) {
        return Math.floorMod(a.ordinal() + b.ordinal(), 12) == 1;
    }

    public static boolean isSamhap(EarthlyBranch a, EarthlyBranch b) {
        return a != b && a.ordinal() % 4 == b.ordinal() % 4;
    }

    /** 삼합이 이루는 오행 (신자진=수 …). 삼합이 아니면 null */
    public static Element samhapElement(EarthlyBranch a, EarthlyBranch b) {
        if (!isSamhap(a, b)) return null;
        return switch (a.ordinal() % 4) {
            case 0 -> Element.WATER;   // 신자진
            case 1 -> Element.METAL;   // 사유축
            case 2 -> Element.FIRE;    // 인오술
            default -> Element.WOOD;   // 해묘미
        };
    }

    public static boolean isChung(EarthlyBranch a, EarthlyBranch b) {
        return Math.floorMod(a.ordinal() - b.ordinal(), 12) == 6;
    }

    public static boolean isHyeong(EarthlyBranch a, EarthlyBranch b) {
        if (a == b) return SELF_HYEONG.contains(a.ordinal());
        return HYEONG_PAIRS.contains(key(a.ordinal(), b.ordinal()));
    }

    public static boolean isHae(EarthlyBranch a, EarthlyBranch b) {
        return Math.floorMod(a.ordinal() + b.ordinal(), 12) == 7;
    }

    public static boolean isPa(EarthlyBranch a, EarthlyBranch b) {
        return a != b && PA_PAIRS.contains(key(a.ordinal(), b.ordinal()));
    }

    /** 두 지지 사이에 성립하는 관계 전부 (없으면 빈 목록) */
    public static List<BranchRelation> between(EarthlyBranch a, EarthlyBranch b) {
        List<BranchRelation> found = new ArrayList<>(2);
        if (isYukhap(a, b)) found.add(YUKHAP);
        if (isSamhap(a, b)) found.add(SAMHAP);
        if (isChung(a, b)) found.add(CHUNG);
        if (isHyeong(a, b)) found.add(HYEONG);
        if (isHae(a, b)) found.add(HAE);
        if (isPa(a, b)) found.add(PA);
        return found;
    }
}
