package com.wordplay.saju.domain;

import java.util.List;

/**
 * 사주 종류 — 같은 사주팔자를 어느 관점으로 풀어줄지 정한다.
 * label/emoji/description 은 화면에, focus/sectionHints 는 AI 프롬프트에 쓰인다.
 */
public enum SajuType {

    TOTAL("종합사주", "🔮", "타고난 기질부터 인생 전반의 흐름까지 한 번에",
            "타고난 기질, 강약점, 인생 전반의 흐름을 균형 있게 짚어준다",
            List.of("타고난 기질", "강점과 약점", "인생의 큰 흐름", "지금 시기의 조언")),

    LOVE("연애사주", "💗", "연애 스타일과 인연이 들어오는 흐름",
            "연애 성향, 끌리는 상대의 유형, 인연이 들어오는 시기를 중심으로 본다",
            List.of("연애할 때의 나", "잘 맞는 상대 유형", "인연의 흐름", "관계에서 조심할 점")),

    WEALTH("재물사주", "💰", "돈이 들어오는 방식과 재물 관리 포인트",
            "재성(財星)의 상태를 중심으로 돈 버는 방식과 지출·투자 성향을 본다",
            List.of("타고난 재물 그릇", "돈이 들어오는 방식", "돈이 새는 구멍", "재물운이 열리는 시기")),

    CAREER("직업사주", "💼", "적성에 맞는 일과 커리어 방향",
            "관성(官星)과 식상(食傷)의 균형으로 적성과 조직 적응력, 커리어 방향을 본다",
            List.of("일할 때의 나", "잘 맞는 일의 결", "조직 vs 독립", "커리어 전환 시기")),

    STUDY("공부사주", "📚", "공부 스타일과 시험·합격 흐름",
            "인성(印星)을 중심으로 학습 스타일, 집중 패턴, 시험운의 흐름을 본다",
            List.of("나에게 맞는 공부법", "집중이 잘 되는 조건", "시험운의 흐름", "슬럼프 대처법")),

    HEALTH("건강사주", "🌿", "타고난 체질과 챙겨야 할 부분",
            "오행의 과부족으로 타고난 체질과 생활 습관에서 챙길 부분을 본다",
            List.of("타고난 체질", "약해지기 쉬운 부분", "생활 습관 처방", "컨디션이 흔들리는 시기")),

    YEARLY("올해의 운세", "🗓️", "세운(歲運)으로 보는 올해의 흐름",
            "올해 세운과 현재 대운의 관계를 중심으로 한 해의 흐름을 본다",
            List.of("올해의 전체 기류", "잘 풀리는 영역", "조심할 영역", "상반기·하반기 흐름"));

    private final String label;
    private final String emoji;
    private final String description;
    private final String focus;
    private final List<String> sectionHints;

    SajuType(String label, String emoji, String description, String focus, List<String> sectionHints) {
        this.label = label;
        this.emoji = emoji;
        this.description = description;
        this.focus = focus;
        this.sectionHints = sectionHints;
    }

    public String label() {
        return label;
    }

    public String emoji() {
        return emoji;
    }

    public String description() {
        return description;
    }

    /** AI에게 주는 해석 관점 */
    public String focus() {
        return focus;
    }

    /** AI에게 제안하는 섹션 주제 */
    public List<String> sectionHints() {
        return sectionHints;
    }
}
