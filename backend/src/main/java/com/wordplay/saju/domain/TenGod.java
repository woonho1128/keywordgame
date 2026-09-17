package com.wordplay.saju.domain;

/**
 * 십성(十星). 일간(日干)을 기준으로 다른 간(干)이 갖는 관계.
 *
 * 판정 규칙:
 *   같은 오행        → 음양 같으면 비견, 다르면 겁재
 *   내가 생하는 오행 → 음양 같으면 식신, 다르면 상관
 *   내가 극하는 오행 → 음양 같으면 편재, 다르면 정재
 *   나를 극하는 오행 → 음양 같으면 편관, 다르면 정관
 *   나를 생하는 오행 → 음양 같으면 편인, 다르면 정인
 */
public enum TenGod {

    BIGYEON("비견", "比肩", "자립·경쟁"),
    GEOPJAE("겁재", "劫財", "추진·경쟁"),
    SIKSIN("식신", "食神", "표현·여유"),
    SANGGWAN("상관", "傷官", "재능·자유"),
    PYEONJAE("편재", "偏財", "유동 재물·활동성"),
    JEONGJAE("정재", "正財", "안정 재물·성실"),
    PYEONGWAN("편관", "偏官", "도전·압박"),
    JEONGGWAN("정관", "正官", "명예·규범"),
    PYEONIN("편인", "偏印", "직관·연구"),
    JEONGIN("정인", "正印", "학문·보호");

    private final String korean;
    private final String hanja;
    private final String keyword;

    TenGod(String korean, String hanja, String keyword) {
        this.korean = korean;
        this.hanja = hanja;
        this.keyword = keyword;
    }

    public String korean() {
        return korean;
    }

    public String hanja() {
        return hanja;
    }

    public String keyword() {
        return keyword;
    }

    /** 비겁·식상·재성·관성·인성 다섯 갈래 중 어디인지 */
    public String group() {
        return switch (this) {
            case BIGYEON, GEOPJAE -> "비겁";
            case SIKSIN, SANGGWAN -> "식상";
            case PYEONJAE, JEONGJAE -> "재성";
            case PYEONGWAN, JEONGGWAN -> "관성";
            case PYEONIN, JEONGIN -> "인성";
        };
    }

    /**
     * 일간 기준으로 대상 천간의 십성을 판정.
     */
    public static TenGod of(HeavenlyStem dayMaster, HeavenlyStem target) {
        Element me = dayMaster.element();
        Element other = target.element();
        boolean samePolarity = dayMaster.isYang() == target.isYang();

        if (me == other) return samePolarity ? BIGYEON : GEOPJAE;
        if (me.generates() == other) return samePolarity ? SIKSIN : SANGGWAN;
        if (me.controls() == other) return samePolarity ? PYEONJAE : JEONGJAE;
        if (other.controls() == me) return samePolarity ? PYEONGWAN : JEONGGWAN;
        return samePolarity ? PYEONIN : JEONGIN;
    }

    /** 지지는 지장간 정기로 판정한다. */
    public static TenGod of(HeavenlyStem dayMaster, EarthlyBranch target) {
        return of(dayMaster, target.mainHiddenStem());
    }
}
