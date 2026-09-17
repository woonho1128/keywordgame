package com.wordplay.saju.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 합·충·형·해·파 판정을 전통 목록과 통째로 대조한다.
 * 일부를 수식(인덱스 합/차)으로 구현했기 때문에, 66개 조합을 전부 돌려
 * 더 잡히거나 빠지는 쌍이 없는지 확인한다.
 */
class RelationTest {

    /** 두 지지의 모든 조합(자기 자신 포함)에서 조건을 만족하는 쌍을 "자축" 형태로 모은다 */
    private List<String> allPairs(BiPredicate<EarthlyBranch, EarthlyBranch> rule, boolean includeSelf) {
        List<String> found = new ArrayList<>();
        EarthlyBranch[] branches = EarthlyBranch.values();
        for (int i = 0; i < branches.length; i++) {
            for (int j = includeSelf ? i : i + 1; j < branches.length; j++) {
                if (rule.test(branches[i], branches[j])) {
                    found.add(branches[i].korean() + branches[j].korean());
                }
            }
        }
        return found;
    }

    @Test
    void 육합은_여섯쌍() {
        assertThat(allPairs(BranchRelation::isYukhap, false))
                .containsExactlyInAnyOrder("자축", "인해", "묘술", "진유", "사신", "오미");
    }

    @Test
    void 충은_여섯쌍() {
        assertThat(allPairs(BranchRelation::isChung, false))
                .containsExactlyInAnyOrder("자오", "축미", "인신", "묘유", "진술", "사해");
    }

    @Test
    void 해는_여섯쌍() {
        assertThat(allPairs(BranchRelation::isHae, false))
                .containsExactlyInAnyOrder("자미", "축오", "인사", "묘진", "신해", "유술");
    }

    @Test
    void 파는_여섯쌍() {
        assertThat(allPairs(BranchRelation::isPa, false))
                .containsExactlyInAnyOrder("자유", "축진", "인해", "묘오", "사신", "미술");
    }

    @Test
    void 형은_삼형_상형_자형() {
        assertThat(allPairs(BranchRelation::isHyeong, true))
                .containsExactlyInAnyOrder(
                        "인사", "사신", "인신",       // 인사신 삼형
                        "축술", "미술", "축미",       // 축술미 삼형
                        "자묘",                       // 상형
                        "진진", "오오", "유유", "해해" // 자형
                );
    }

    @Test
    void 삼합은_네개_국() {
        // 신자진 수국 / 해묘미 목국 / 인오술 화국 / 사유축 금국
        assertThat(BranchRelation.samhapElement(EarthlyBranch.SIN, EarthlyBranch.JA)).isEqualTo(Element.WATER);
        assertThat(BranchRelation.samhapElement(EarthlyBranch.JA, EarthlyBranch.JIN)).isEqualTo(Element.WATER);
        assertThat(BranchRelation.samhapElement(EarthlyBranch.HAE, EarthlyBranch.MYO)).isEqualTo(Element.WOOD);
        assertThat(BranchRelation.samhapElement(EarthlyBranch.IN, EarthlyBranch.SUL)).isEqualTo(Element.FIRE);
        assertThat(BranchRelation.samhapElement(EarthlyBranch.SA, EarthlyBranch.CHUK)).isEqualTo(Element.METAL);

        // 다른 국끼리는 삼합이 아니다
        assertThat(BranchRelation.isSamhap(EarthlyBranch.JA, EarthlyBranch.CHUK)).isFalse();
        assertThat(BranchRelation.samhapElement(EarthlyBranch.JA, EarthlyBranch.CHUK)).isNull();
        // 같은 글자는 삼합으로 치지 않는다
        assertThat(BranchRelation.isSamhap(EarthlyBranch.JA, EarthlyBranch.JA)).isFalse();

        // 각 국은 정확히 3쌍씩 = 총 12쌍
        assertThat(allPairs(BranchRelation::isSamhap, false)).hasSize(12);
    }

    @Test
    void 인해는_육합이면서_파다() {
        assertThat(BranchRelation.between(EarthlyBranch.IN, EarthlyBranch.HAE))
                .containsExactlyInAnyOrder(BranchRelation.YUKHAP, BranchRelation.PA);
    }

    @Test
    void 관계가_없는_쌍도_있다() {
        // 자-신 은 삼합(신자진)만 걸린다
        assertThat(BranchRelation.between(EarthlyBranch.JA, EarthlyBranch.SIN))
                .containsExactly(BranchRelation.SAMHAP);
        // 축-인 은 아무 관계도 없다
        assertThat(BranchRelation.between(EarthlyBranch.CHUK, EarthlyBranch.IN)).isEmpty();
    }

    @Test
    void 천간합은_다섯쌍이고_합화오행이_맞다() {
        List<String> hap = new ArrayList<>();
        HeavenlyStem[] stems = HeavenlyStem.values();
        for (int i = 0; i < stems.length; i++) {
            for (int j = i + 1; j < stems.length; j++) {
                if (StemRelation.isHap(stems[i], stems[j])) {
                    hap.add(stems[i].korean() + stems[j].korean());
                }
            }
        }
        assertThat(hap).containsExactlyInAnyOrder("갑기", "을경", "병신", "정임", "무계");

        assertThat(StemRelation.hapElement(HeavenlyStem.GAP, HeavenlyStem.GI)).isEqualTo(Element.EARTH);
        assertThat(StemRelation.hapElement(HeavenlyStem.EUL, HeavenlyStem.GYEONG)).isEqualTo(Element.METAL);
        assertThat(StemRelation.hapElement(HeavenlyStem.BYEONG, HeavenlyStem.SIN)).isEqualTo(Element.WATER);
        assertThat(StemRelation.hapElement(HeavenlyStem.JEONG, HeavenlyStem.IM)).isEqualTo(Element.WOOD);
        assertThat(StemRelation.hapElement(HeavenlyStem.MU, HeavenlyStem.GYE)).isEqualTo(Element.FIRE);
        assertThat(StemRelation.hapElement(HeavenlyStem.GAP, HeavenlyStem.EUL)).isNull();
    }

    @Test
    void 천간충은_네쌍이고_무기는_충하지_않는다() {
        List<String> chung = new ArrayList<>();
        HeavenlyStem[] stems = HeavenlyStem.values();
        for (int i = 0; i < stems.length; i++) {
            for (int j = i + 1; j < stems.length; j++) {
                if (StemRelation.isChung(stems[i], stems[j])) {
                    chung.add(stems[i].korean() + stems[j].korean());
                }
            }
        }
        assertThat(chung).containsExactlyInAnyOrder("갑경", "을신", "병임", "정계");
        assertThat(StemRelation.isChung(HeavenlyStem.MU, HeavenlyStem.GAP)).isFalse();
        assertThat(StemRelation.isChung(HeavenlyStem.GI, HeavenlyStem.EUL)).isFalse();
    }
}
