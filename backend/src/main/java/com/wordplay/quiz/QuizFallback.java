package com.wordplay.quiz;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AI 없이도 게임이 돌아가게 하는 내장 문제.
 *
 * <p>OpenAI 키가 없거나, 하루 호출 한도를 넘겼거나, 응답이 실패했을 때 쓴다. 판이
 * 시작조차 못 하는 것보다 준비된 문제라도 내는 게 낫다. 난이도는 대략만 맞춘다.
 */
final class QuizFallback {

    private QuizFallback() {}

    private static QuizQuestion choice(int level, String topic, String q, String a, String b, String c, String d, int idx, String ex) {
        return new QuizQuestion(Integer.toHexString(q.hashCode()), topic, level,
                QuizQuestion.Kind.CHOICE, q, List.of(a, b, c, d), idx, List.of(), ex);
    }

    private static QuizQuestion text(int level, String topic, String q, String ex, String... answers) {
        return new QuizQuestion(Integer.toHexString(q.hashCode()), topic, level,
                QuizQuestion.Kind.TEXT, q, List.of(), -1, List.of(answers), ex);
    }

    private static final List<QuizQuestion> ALL = List.of(
            // 쉬움 (1~3)
            choice(2, "나라·수도", "대한민국의 수도는?", "부산", "서울", "인천", "대구", 1, "1394년부터 조선의 도읍이었다."),
            choice(2, "동물", "다음 중 포유류가 아닌 동물은?", "돌고래", "박쥐", "펭귄", "고래", 2, "펭귄은 날지 못하는 새다."),
            choice(2, "과학", "물이 얼는 온도는 섭씨 몇 도인가?", "0도", "10도", "-10도", "100도", 0, "1기압에서 물의 어는점이다."),
            text(2, "동물", "목이 가장 긴 육상 동물의 이름은?", "목뼈는 사람과 같이 7개다.", "기린"),
            text(3, "나라·수도", "일본의 수도는? (도시 이름)", "1868년 교토에서 옮겨왔다.", "도쿄", "동경"),
            choice(3, "우주·천문", "태양계에서 가장 큰 행성은?", "지구", "토성", "목성", "화성", 2, "지구 지름의 약 11배다."),
            text(3, "인체·의학", "사람의 몸에서 피를 온몸으로 보내는 장기는?", "1분에 약 5리터를 내보낸다.", "심장"),

            // 보통 (4~6)
            choice(5, "한국사", "훈민정음을 창제한 조선의 임금은?", "태종", "세종", "성종", "정조", 1, "1443년 창제해 1446년 반포했다."),
            choice(5, "지리·자연", "세계에서 가장 높은 산은?", "K2", "에베레스트", "킬리만자로", "몽블랑", 1, "해발 약 8,849m다."),
            text(5, "바다·해양", "지구에서 가장 넓은 바다의 이름은?", "지구 표면의 약 3분의 1을 덮는다.", "태평양"),
            choice(5, "미술", "'모나리자'를 그린 화가는?", "미켈란젤로", "라파엘로", "레오나르도 다빈치", "고흐", 2, "16세기 초 작품으로 루브르에 있다."),
            text(6, "지리·자연", "이집트를 지나 지중해로 흘러드는, 아프리카의 긴 강은?", "고대 이집트 문명이 이 강가에서 일어났다.", "나일강", "나일 강", "나일"),
            choice(6, "과학", "물의 화학식은?", "CO2", "H2O", "O2", "NaCl", 1, "수소 둘과 산소 하나로 이루어진다."),
            text(6, "한국사", "임진왜란에서 학익진으로 왜군을 물리친 조선의 장군은?", "한산도 대첩을 이끌었다.", "이순신", "충무공 이순신", "충무공"),
            choice(6, "음악", "피아노 건반은 보통 몇 개인가?", "76개", "88개", "92개", "64개", 1, "흰 건반 52개와 검은 건반 36개다."),

            // 어려움 (7~10)
            choice(8, "공룡", "티라노사우루스가 살았던 지질 시대는?", "트라이아스기", "쥐라기", "백악기", "페름기", 2, "약 6,800만 년 전 백악기 말이다."),
            text(8, "우주·천문", "태양에서 가장 가까운 행성의 이름은?", "공전 주기가 약 88일이다.", "수성"),
            choice(8, "언어·어원", "'로봇(robot)'이라는 말이 처음 쓰인 나라의 언어는?", "영어", "체코어", "독일어", "러시아어", 1, "카렐 차페크의 희곡에서 왔다."),
            text(9, "곤충", "번데기 과정을 거치지 않고 자라는 곤충의 변태를 무엇이라 하는가?", "메뚜기·잠자리가 이에 해당한다.", "불완전변태"),
            choice(9, "건축·유적", "이스탄불에 있는, 비잔티움 제국이 세운 대성당은?", "성 베드로 대성당", "아야 소피아", "노트르담", "웨스트민스터", 1, "537년에 완공되었다."),
            text(10, "과학", "원자 번호 79번인 금속 원소의 이름은?", "원소 기호는 Au다.", "금", "황금"));

    /**
     * 요청 난이도에 가까운 문제를 골라준다.
     *
     * @param exclude 이미 뽑힌 문제들(같은 문제를 두 번 넣지 않는다)
     */
    static List<QuizQuestion> pick(int level, int count, Collection<QuizQuestion> exclude) {
        Set<String> used = new HashSet<>();
        for (QuizQuestion q : exclude) used.add(q.id());

        List<QuizQuestion> cand = new ArrayList<>();
        for (QuizQuestion q : ALL) if (!used.contains(q.id())) cand.add(q);
        // 요청 난이도와 가까운 순으로, 같은 거리면 무작위로.
        Collections.shuffle(cand, ThreadLocalRandom.current());
        cand.sort((a, b) -> Math.abs(a.level() - level) - Math.abs(b.level() - level));
        return new ArrayList<>(cand.subList(0, Math.min(count, cand.size())));
    }

    static int size() { return ALL.size(); }
}
