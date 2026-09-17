package com.wordplay.saju.domain;

import java.util.List;

/**
 * 궁합 종류 — 같은 두 사주를 어떤 관계로 놓고 볼지 정한다.
 */
public enum CompatType {

    LOVE("연인궁합", "💕", "연애할 때 두 사람이 어떻게 맞물리는지",
            "연애 감정의 결, 끌림의 방향, 다투는 지점과 풀리는 지점을 본다",
            List.of("첫인상과 끌림", "둘이 함께일 때의 공기", "부딪치는 지점",
                    "서로에게 주는 것", "관계가 깊어지는 방법", "올해 이 관계의 흐름")),

    COUPLE("부부궁합", "💍", "오래 같이 살 때의 합",
            "일지(배우자 자리)를 중심으로 생활 리듬, 재물 흐름, 오래 갈 때의 마찰을 본다",
            List.of("생활 리듬의 합", "배우자 자리로 본 인연", "돈과 살림의 합",
                    "오래 가면 드러나는 마찰", "서로를 보완하는 부분", "함께 넘어야 할 고비")),

    FRIEND("친구궁합", "🤝", "친구로 지낼 때의 케미",
            "부담 없이 오래 가는 사이인지, 함께 있으면 에너지가 오르는지를 본다",
            List.of("같이 있을 때의 케미", "말이 잘 통하는 지점", "서운해지는 지점",
                    "함께하면 좋은 일", "오래 가는 법", "올해 둘 사이의 흐름")),

    WORK("동료궁합", "💼", "같이 일할 때의 합",
            "역할 분담, 의사결정 방식, 동업이나 협업에서의 위험 지점을 본다",
            List.of("일할 때의 호흡", "누가 어떤 역할에 맞나", "의견이 갈리는 지점",
                    "함께 잘하는 일", "동업할 때 조심할 것", "올해 협업의 흐름")),

    FAMILY("가족궁합", "👨‍👩‍👧", "가족으로 엮인 사이의 합",
            "가까운 만큼 부딪치는 부분과, 서로에게 힘이 되는 부분을 본다",
            List.of("서로를 보는 눈", "편안한 지점", "부딪치는 지점",
                    "서로에게 힘이 되는 법", "거리를 둘 부분", "올해 이 관계의 흐름"));

    private final String label;
    private final String emoji;
    private final String description;
    private final String focus;
    private final List<String> sectionHints;

    CompatType(String label, String emoji, String description, String focus, List<String> sectionHints) {
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

    public String focus() {
        return focus;
    }

    public List<String> sectionHints() {
        return sectionHints;
    }
}
