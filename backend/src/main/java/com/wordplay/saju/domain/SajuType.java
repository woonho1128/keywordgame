package com.wordplay.saju.domain;

import java.util.List;

/**
 * 사주 종류 — 같은 사주팔자를 어느 관점으로 풀어줄지 정한다.
 * label/emoji/description 은 화면에, focus/sectionHints 는 AI 프롬프트에 쓰인다.
 */
public enum SajuType {

    TOTAL("종합사주", "🔮", "타고난 기질부터 인생 전반의 흐름까지 한 번에",
            "타고난 기질, 강약점, 인생 전반의 흐름을 균형 있게 짚어준다",
            List.of("타고난 기질과 성격",
                    "남들 눈에 비치는 나",
                    "타고난 강점",
                    "발목을 잡는 약점",
                    "인생 전체의 큰 흐름",
                    "지금 대운에서 할 일"),
            "한마디로 이 사람은, 가장 강한 기운, 지금 시기 키워드"),

    LOVE("연애사주", "💗", "연애 스타일과 인연이 들어오는 흐름",
            "연애 성향, 끌리는 상대의 유형, 인연이 들어오는 시기를 중심으로 본다",
            List.of("연애할 때의 나",
                    "끌리는 상대 유형",
                    "실제로 잘 맞는 상대",
                    "연애에서 반복되는 패턴",
                    "인연이 들어오는 시기",
                    "오래 가져가는 법"),
            "연애 스타일 한마디, 잘 맞는 상대 유형, 인연이 들어오는 시기"),

    WEALTH("재물사주", "💰", "돈이 들어오는 방식과 재물 관리 포인트",
            "재성(財星)의 상태를 중심으로 돈 버는 방식과 지출·투자 성향을 본다",
            List.of("타고난 재물 그릇",
                    "돈이 들어오는 방식",
                    "돈이 새는 구멍",
                    "나에게 맞는 돈 관리",
                    "재물운이 열리는 시기",
                    "재물에서 조심할 것"),
            "타고난 재물 그릇, 돈이 들어오는 방식, 재물운이 열리는 시기"),

    CAREER("직업사주", "💼", "적성에 맞는 일과 커리어 방향",
            "관성(官星)과 식상(食傷)의 균형으로 적성과 조직 적응력, 커리어 방향을 본다",
            List.of("일할 때의 나",
                    "잘 맞는 일의 결",
                    "조직이 맞나 독립이 맞나",
                    "함께 일하면 좋은 사람",
                    "커리어가 바뀌는 시기",
                    "더 크려면 채울 것"),
            "적성 한마디, 조직이 맞나 독립이 맞나, 커리어 전환 시기"),

    STUDY("공부사주", "📚", "공부 스타일과 시험·합격 흐름",
            "인성(印星)을 중심으로 학습 스타일, 집중 패턴, 시험운의 흐름을 본다",
            List.of("나에게 맞는 공부법",
                    "집중이 잘 되는 조건",
                    "잘 맞는 분야",
                    "시험운의 흐름",
                    "슬럼프가 오는 패턴",
                    "합격을 당기는 습관"),
            "공부 유형 한마디, 잘 맞는 분야, 시험운이 좋은 시기"),

    HEALTH("건강사주", "🌿", "타고난 체질과 챙겨야 할 부분",
            "오행의 과부족으로 타고난 체질과 생활 습관에서 챙길 부분을 본다",
            List.of("타고난 체질",
                    "약해지기 쉬운 부분",
                    "생활 습관 처방",
                    "맞는 음식과 운동",
                    "컨디션이 흔들리는 시기",
                    "마음 건강 챙기기"),
            "타고난 체질, 약해지기 쉬운 곳, 지금 챙길 것"),

    YEARLY("올해의 운세", "🗓️", "세운(歲運)으로 보는 올해의 흐름",
            "올해 세운과 현재 대운의 관계를 중심으로 한 해의 흐름을 본다",
            List.of("올해의 전체 기류",
                    "상반기 흐름",
                    "하반기 흐름",
                    "잘 풀리는 영역",
                    "조심할 영역",
                    "올해 꼭 해야 할 것"),
            "올해를 한마디로, 잘 풀리는 영역, 조심할 시기"),

    FUTURE_LOVE("미래인연", "🔗", "앞으로 만날 인연은 어디서, 몇 번, 언제",
            "일지(배우자 자리)와 재성·관성, 대운·세운의 흐름으로 인연이 들어오는 자리와 시기를 본다. "
                    + "횟수는 단정하지 말고 '흐름상 이 정도로 본다'는 식으로 말한다",
            List.of("인연이 오는 자리",
                    "앞으로의 인연 횟수",
                    "가장 가까운 인연 시기",
                    "그 사람의 인상",
                    "놓치기 쉬운 순간",
                    "인연을 당기는 법"),
            "앞으로 만날 인연 횟수(숫자로), 가장 유력한 자리, 가장 가까운 시기");

    private final String label;
    private final String emoji;
    private final String description;
    private final String focus;
    private final List<String> sectionHints;
    private final String highlightHint;

    SajuType(String label, String emoji, String description, String focus,
             List<String> sectionHints, String highlightHint) {
        this.label = label;
        this.emoji = emoji;
        this.description = description;
        this.focus = focus;
        this.sectionHints = sectionHints;
        this.highlightHint = highlightHint;
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

    /** 결과 맨 위에 한눈에 보여줄 요점(highlights)으로 뭘 뽑을지 */
    public String highlightHint() {
        return highlightHint;
    }
}
