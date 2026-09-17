package com.wordplay.saju.service;

import com.wordplay.saju.domain.BranchRelation;
import com.wordplay.saju.domain.CompatSignal;
import com.wordplay.saju.domain.Compatibility;
import com.wordplay.saju.domain.EarthlyBranch;
import com.wordplay.saju.domain.Element;
import com.wordplay.saju.domain.FourPillars;
import com.wordplay.saju.domain.HeavenlyStem;
import com.wordplay.saju.domain.Pillar;
import com.wordplay.saju.domain.StemRelation;
import com.wordplay.saju.domain.TenGod;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 두 사람의 사주를 맞대어 궁합 근거를 뽑는다.
 *
 * <p>사주 해석과 같은 원칙: 관계 판정(합·충·형·해·파, 십성, 오행 보완)은 전부 여기서
 * 결정론적으로 계산하고, AI는 그 사실을 말로 풀기만 한다. 같은 두 사람이면 점수도 항상 같다.
 *
 * <h3>자리별 비중</h3>
 * <b>일지</b>가 가장 크다 — 배우자 자리라 연인·부부 궁합의 핵심으로 본다.
 * 그다음이 <b>년지</b>(띠)와 <b>월지</b>(사회적 기질), <b>시지</b>는 가장 약하게 본다.
 *
 * <p>가중치 자체는 명리 고전에 숫자로 정해진 게 아니라 이 서비스의 기준이다.
 * 점수는 재미 요소고, 실제 해석은 어떤 관계가 어느 자리에 걸렸는지가 더 중요하다.
 */
@Component
public class CompatibilityAnalyzer {

    /** 아무 관계도 없을 때의 점수 — 무난한 사이 */
    private static final int BASE_SCORE = 62;
    private static final int MIN_SCORE = 30;
    private static final int MAX_SCORE = 98;

    /** 자리별 배율 — 일지가 가장 무겁다 */
    private record Position(String name, double weight) {}

    private static final Position DAY = new Position("일지", 1.0);
    private static final Position YEAR = new Position("년지", 0.5);
    private static final Position MONTH = new Position("월지", 0.5);
    private static final Position HOUR = new Position("시지", 0.3);

    /** 관계별 기본 점수 (일지 기준) */
    private static int baseWeight(BranchRelation relation) {
        return switch (relation) {
            case YUKHAP -> 14;
            case SAMHAP -> 11;
            case CHUNG -> -13;
            case HYEONG -> -8;
            case HAE -> -6;
            case PA -> -4;
        };
    }

    public Compatibility analyze(FourPillars a, FourPillars b) {
        List<CompatSignal> signals = new ArrayList<>();

        addBranchSignals(signals, DAY, a.day(), b.day());
        addBranchSignals(signals, YEAR, a.year(), b.year());
        addBranchSignals(signals, MONTH, a.month(), b.month());
        if (a.hourKnown() && b.hourKnown()) {
            addBranchSignals(signals, HOUR, a.hour(), b.hour());
        }

        addDayStemSignals(signals, a.dayMaster(), b.dayMaster());

        TenGod aSeesB = TenGod.of(a.dayMaster(), b.dayMaster());
        TenGod bSeesA = TenGod.of(b.dayMaster(), a.dayMaster());
        signals.add(new CompatSignal("일간", "십성",
                "A가 보는 B는 " + aSeesB.korean() + "(" + aSeesB.keyword() + "), "
                        + "B가 보는 A는 " + bSeesA.korean() + "(" + bSeesA.keyword() + ")",
                tenGodWeight(aSeesB) + tenGodWeight(bSeesA)));

        List<Element> aFilledByB = complement(a, b);
        List<Element> bFilledByA = complement(b, a);
        if (!aFilledByB.isEmpty()) {
            signals.add(new CompatSignal("오행", "보완",
                    "A에게 없는 " + korean(aFilledByB) + " 기운을 B가 채워준다",
                    Math.min(aFilledByB.size() * 4, 12)));
        }
        if (!bFilledByA.isEmpty()) {
            signals.add(new CompatSignal("오행", "보완",
                    "B에게 없는 " + korean(bFilledByA) + " 기운을 A가 채워준다",
                    Math.min(bFilledByA.size() * 4, 12)));
        }

        int score = BASE_SCORE + signals.stream().mapToInt(CompatSignal::weight).sum();
        score = Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));

        return new Compatibility(
                signals, score, aSeesB, bSeesA, aFilledByB, bFilledByA,
                StemRelation.hapElement(a.dayMaster(), b.dayMaster())
        );
    }

    private void addBranchSignals(List<CompatSignal> signals, Position position,
                                  Pillar aPillar, Pillar bPillar) {
        EarthlyBranch a = aPillar.branch();
        EarthlyBranch b = bPillar.branch();

        for (BranchRelation relation : BranchRelation.between(a, b)) {
            String detail = a.korean() + "-" + b.korean() + " " + relation.korean();
            if (relation == BranchRelation.SAMHAP) {
                Element element = BranchRelation.samhapElement(a, b);
                detail += "(" + element.korean() + "국)";
            }
            detail += " — " + relation.meaning();

            signals.add(new CompatSignal(
                    position.name(), relation.korean(), detail,
                    (int) Math.round(baseWeight(relation) * position.weight())
            ));
        }
    }

    private void addDayStemSignals(List<CompatSignal> signals, HeavenlyStem a, HeavenlyStem b) {
        if (StemRelation.isHap(a, b)) {
            Element element = StemRelation.hapElement(a, b);
            signals.add(new CompatSignal("일간", "합",
                    a.korean() + "-" + b.korean() + " 천간합(" + element.korean() + ") — 서로를 끌어당긴다",
                    12));
        }
        if (StemRelation.isChung(a, b)) {
            signals.add(new CompatSignal("일간", "충",
                    a.korean() + "-" + b.korean() + " 천간충 — 생각이 정면으로 갈린다",
                    -9));
        }
    }

    /**
     * 십성 관계 가중치.
     * 정(正) 계열(정재·정관·정인·식신)은 편하게 맞물리고,
     * 편(偏) 계열 중 겁재·상관·편관은 서로 긁는 쪽으로 본다.
     */
    private int tenGodWeight(TenGod tenGod) {
        return switch (tenGod) {
            case JEONGJAE, JEONGGWAN, JEONGIN, SIKSIN -> 5;
            case BIGYEON -> 3;
            case PYEONJAE, PYEONIN -> 1;
            case GEOPJAE, SANGGWAN, PYEONGWAN -> -3;
        };
    }

    /** target에게 없는 오행 중 source가 2개 이상 가진 것 */
    private List<Element> complement(FourPillars target, FourPillars source) {
        Map<Element, Integer> sourceCounts = new EnumMap<>(source.elementCounts());
        return target.missingElements().stream()
                .filter(e -> sourceCounts.getOrDefault(e, 0) >= 2)
                .toList();
    }

    private String korean(List<Element> elements) {
        return String.join("·", elements.stream().map(Element::korean).toList());
    }
}
