package com.wordplay.saju.service;

import com.wordplay.saju.domain.CompatSignal;
import com.wordplay.saju.domain.CompatType;
import com.wordplay.saju.domain.Compatibility;
import com.wordplay.saju.domain.Element;
import com.wordplay.saju.domain.FourPillars;
import com.wordplay.saju.domain.Gender;
import com.wordplay.saju.domain.HeavenlyStem;
import com.wordplay.saju.domain.LuckCycle;
import com.wordplay.saju.domain.Pillar;
import com.wordplay.saju.domain.SajuType;
import com.wordplay.saju.domain.TenGod;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * AI 프롬프트 조립기.
 *
 * <p>핵심 전제: 사주팔자·대운·세운·궁합 관계는 {@link SajuCalculator}와
 * {@link CompatibilityAnalyzer}가 이미 정확히 계산했다. AI는 계산하지 않고 "해석"만 한다.
 * 그래서 프롬프트에 계산 결과를 사실로 못박아 넣는다.
 */
@Component
public class SajuPromptBuilder {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** 사주·궁합에 공통으로 적용하는 태도 */
    private static final String COMMON_RULES = """
            지켜야 할 원칙:
            1. 사주팔자, 오행 분포, 십성, 대운, 세운, 합·충 관계는 이미 정확하게 계산되어 주어집니다.
               절대 다시 계산하거나 다른 간지를 지어내지 마세요. 주어진 값만 근거로 씁니다.
            2. 해석에는 반드시 근거를 드러내세요. "일간 OO이 ~", "월지 OO이 ~", "재성이 3개나 있어서 ~"
               처럼 주어진 데이터를 콕 집어 인용하고, 처음 보는 사람도 알아듣게 풀어서 설명합니다.
               사주 용어를 쓸 땐 괄호로 짧게 뜻을 달아주세요. 예: 식상(표현하고 만들어내는 기운)
            3. 사주 용어는 설명에만 쓰고, 답 자체는 생활 언어로 씁니다.
               "인성의 자리", "재성이 들어오는 곳" 같은 말은 장소에 대한 답이 되지 못합니다.
               장소를 물으면 "일터·업무 모임", "친구 소개 자리", "운동·취미 모임", "온라인 커뮤니티",
               "학교·스터디", "여행지"처럼 실제로 갈 수 있는 곳으로 답하세요.
            4. 누구에게나 들어맞는 문장은 쓰지 마세요. "당신은 노력형입니다" 같은 말은 금지입니다.
               이 사주에서만 나올 수 있는 이야기를 구체적인 장면과 함께 씁니다.
               예: "회의에서 말이 길어지면 먼저 결론을 내버리는 편"
            5. 따뜻하고 구체적으로 씁니다. 겁을 주거나 불행을 단정하지 않습니다.
               부정적인 부분은 "이렇게 하면 낫다"는 대안과 함께 말합니다.
            6. 질병 진단, 투자 권유, 법률 판단은 확정적으로 말하지 않습니다.
               건강은 생활 습관 수준으로, 재물은 성향 수준으로만 이야기합니다.
            7. 존댓말로 쓰되 상담하듯 편안한 문장을 씁니다. 개조식이 아니라 줄글로 씁니다.
            """;

    private static final String SYSTEM_PROMPT = """
            당신은 사주명리를 20년 넘게 공부한 상담가입니다. 한국어로만 답합니다.

            %s
            8. 분량을 충분히 쓰세요. 짧게 끊으면 상담이 되지 않습니다.
               각 섹션 본문은 5~8문장으로, 근거 → 해석 → 실제 장면 → 조언 순으로 풀어주세요.

            출력은 아래 JSON 하나만 내보냅니다. 코드블록, 설명, 인사말을 붙이지 마세요.
            {
              "headline": "한 줄 총평 (25자 이내, 이 사람만의 표현으로)",
              "summary": "전체 요약 (5~6문장, 사주 근거를 한 번 이상 인용)",
              "highlights": [
                { "label": "항목 이름 (10자 이내)", "value": "짧은 답 (12자 이내)", "detail": "그렇게 본 근거 한 문장" }
              ],
              "sections": [
                { "title": "소제목 (12자 이내)", "body": "본문 (5~8문장)" }
              ],
              "strengths": ["타고난 강점 (각 1~2문장, 근거 포함)"],
              "cautions": ["조심할 점 (각 1~2문장, 대안까지)"],
              "timeline": [
                { "period": "구간 이름 (예: 30~39세 무신 대운)", "body": "그 시기의 흐름 (2~3문장)" }
              ],
              "encounters": [
                {
                  "year": "연도와 간지 (예: 2027년 정미년)",
                  "past": "지난 해면 true, 앞으로면 false",
                  "where": "어디서 — 실제 장소나 상황 (예: 일터·업무 모임)",
                  "story": "뭐 하다 만나는지 / 그때 어떤 기회였는지 (1~2문장)",
                  "partner": {
                    "age": "나이대 (예: 또래~2살 연상)",
                    "job": "직업의 결 + 예시 (예: 기획·마케팅 쪽)",
                    "vibe": "성격 한 줄 (예: 조용한데 할 말은 하는 편)"
                  },
                  "basis": "그렇게 본 사주 근거 (한 구절)"
                }
              ],
              "keywords": ["핵심 키워드 (각 6자 이내)"],
              "lucky": {
                "color": "행운의 색",
                "number": "행운의 숫자",
                "direction": "도움이 되는 방향",
                "item": "지니면 좋은 것"
              },
              "advice": "지금 바로 실천할 조언 (3~4문장)",
              "score": 0에서 100 사이 정수
            }
            highlights 3개, sections 6개, strengths 3~4개, cautions 2~3개, timeline 3개, keywords 5개로 맞춰주세요.
            encounters 는 "미래인연"을 요청받았을 때만 채우고, 다른 주제면 빈 배열([])로 둡니다.
            highlights 의 value 는 카드처럼 한눈에 읽히게 짧게 씁니다. 횟수를 물으면 "3번"처럼
            숫자로 답하되, 단정이 아니라 흐름상의 짐작임이 detail 에서 드러나게 하세요.
            timeline 은 주어진 대운·세운 데이터를 그대로 쓰고 없는 간지를 만들지 마세요.
            """.formatted(COMMON_RULES);

    private static final String COMPAT_SYSTEM_PROMPT = """
            당신은 사주명리로 궁합을 봐주는 상담가입니다. 한국어로만 답합니다.

            %s
            8. 두 사람의 관계를 봅니다. 한 사람만 칭찬하거나 한 사람을 탓하지 마세요.
               "A는 이래서 B에게 이렇게 보인다" 처럼 양방향으로 풀어주세요.
            9. 궁합 점수와 합·충 관계는 이미 계산되어 주어집니다. 주어진 점수와 어긋나는
               이야기를 쓰지 마세요. 점수가 낮아도 "맞출 수 있는 방법"을 반드시 같이 씁니다.
            10. 각 섹션 본문은 5~8문장으로 충분히 풀어주세요.

            출력은 아래 JSON 하나만 내보냅니다. 코드블록, 설명, 인사말을 붙이지 마세요.
            {
              "headline": "두 사람 관계 한 줄 요약 (25자 이내)",
              "summary": "전체 요약 (5~6문장, 어떤 합/충이 걸렸는지 근거 인용)",
              "sections": [
                { "title": "소제목 (12자 이내)", "body": "본문 (5~8문장)" }
              ],
              "strengths": ["이 조합의 좋은 점 (각 1~2문장, 근거 포함)"],
              "cautions": ["부딪칠 수 있는 점 (각 1~2문장, 대처법까지)"],
              "aToB": "A가 B에게 해주면 좋은 것 (2~3문장)",
              "bToA": "B가 A에게 해주면 좋은 것 (2~3문장)",
              "keywords": ["이 관계를 표현하는 키워드 (각 6자 이내)"],
              "advice": "둘이 오래 잘 지내는 법 (3~4문장)"
            }
            sections 5개, strengths 2~3개, cautions 2~3개, keywords 4개로 맞춰주세요.
            """.formatted(COMMON_RULES);

    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    public String compatSystemPrompt() {
        return COMPAT_SYSTEM_PROMPT;
    }

    /** 계산된 사주를 사실 목록으로 정리해 사용자 프롬프트를 만든다 */
    public String userPrompt(SajuType type, String nickname, Gender gender, FourPillars p) {
        StringBuilder sb = new StringBuilder();

        sb.append("[상담 대상]\n");
        sb.append("- 호칭: ").append(callName(nickname)).append('\n');
        sb.append("- 성별: ").append(gender.korean()).append('\n');
        sb.append("- 나이: 세는나이 ").append(p.koreanAge()).append("세\n");
        appendBirth(sb, p);

        appendChart(sb, p);
        appendLuck(sb, p);
        appendYearSeries(sb, p);
        if (type == SajuType.FUTURE_LOVE) {
            appendSpouseStar(sb, p, gender);
        }

        sb.append("\n[요청] ").append(type.label()).append('\n');
        sb.append("- 관점: ").append(type.focus()).append('\n');
        sb.append("- sections 6개는 이 주제로 구성해주세요: ")
                .append(String.join(" / ", type.sectionHints())).append('\n');
        sb.append("- highlights 3개는 이런 항목으로 뽑아주세요: ").append(type.highlightHint()).append('\n');
        if (type == SajuType.FUTURE_LOVE) {
            sb.append("- encounters 를 5개 채워주세요: 지난 기회 2개(past=true) + 앞으로 3개(past=false).\n");
            sb.append("  · 연도는 위 [연도별 세운] 표에서만 고르고, 왜 그 해인지 basis 에 간지 근거를 답니다.\n");
            sb.append("  · where 는 \"일터·업무 모임\", \"친구 소개 자리\", \"운동·취미 모임\", \"온라인 커뮤니티\",\n");
            sb.append("    \"학교·스터디\", \"여행지\"처럼 실제로 갈 수 있는 곳으로 씁니다. 사주 용어는 쓰지 마세요.\n");
            sb.append("  · story 는 무엇을 하다가 만나는지를 장면으로 씁니다.\n");
            sb.append("    예: 같은 프로젝트에 묶여 몇 달 붙어 일하다 가까워지는 흐름\n");
            sb.append("  · 지난 기회는 \"그때 이런 자리에서 기회가 있었다\"는 식으로 돌아보며 씁니다.\n");
            sb.append("  · partner 에 그 상대가 어떤 사람일지 짧게 적습니다. 위 [배우자 자리]를 근거로 쓰세요.\n");
            sb.append("    - age: 배우자성이 연주·월주 쪽이면 연상, 시주 쪽이면 연하로 보고 \"또래\", \"2~4살 연상\" 처럼\n");
            sb.append("    - job: 배우자성의 오행에서 읽히는 직업의 결을 실제 직군으로 (목=교육·출판·기획,\n");
            sb.append("      화=미디어·디자인·IT, 토=부동산·건설·공공, 금=금융·법·기계·의료, 수=유통·연구·서비스)\n");
            sb.append("    - vibe: 성격 한 줄. 일지의 십성과 오행에서 끌어옵니다\n");
            sb.append("    셋 다 단정하지 말고 \"이런 결의 사람일 가능성이 크다\"는 톤으로 씁니다.\n");
            sb.append("- highlights 의 \"앞으로 만날 인연\" 횟수는 past=false 인 encounters 개수와 같아야 합니다.\n");
            sb.append("- highlights 의 \"가장 유력한 자리\"도 실제 장소로 답하세요.\n");
        }
        sb.append("- score 는 이 주제(").append(type.label())
                .append(")에 한정한 운세 점수입니다.\n");

        return sb.toString();
    }

    /** 두 사람의 사주 + 계산된 관계를 정리해 궁합 프롬프트를 만든다 */
    public String compatUserPrompt(CompatType type,
                                   String aName, Gender aGender, FourPillars a,
                                   String bName, Gender bGender, FourPillars b,
                                   Compatibility compatibility) {
        StringBuilder sb = new StringBuilder();

        sb.append("[A] ").append(callName(aName))
                .append(" · ").append(aGender.korean())
                .append(" · 세는나이 ").append(a.koreanAge()).append("세\n");
        appendBirth(sb, a);
        appendChart(sb, a);
        appendCurrentLuck(sb, a);

        sb.append("\n[B] ").append(callName(bName))
                .append(" · ").append(bGender.korean())
                .append(" · 세는나이 ").append(b.koreanAge()).append("세\n");
        appendBirth(sb, b);
        appendChart(sb, b);
        appendCurrentLuck(sb, b);

        sb.append("\n[계산된 관계]\n");
        sb.append("- 궁합 점수: ").append(compatibility.score()).append("점 (이 점수에 맞춰 써주세요)\n");
        sb.append("- A의 일간이 보는 B: ").append(compatibility.aSeesB().korean())
                .append("(").append(compatibility.aSeesB().keyword()).append(")\n");
        sb.append("- B의 일간이 보는 A: ").append(compatibility.bSeesA().korean())
                .append("(").append(compatibility.bSeesA().keyword()).append(")\n");
        if (compatibility.dayStemHapElement() != null) {
            sb.append("- 두 일간이 천간합(")
                    .append(compatibility.dayStemHapElement().korean()).append(")을 이룬다\n");
        }
        for (CompatSignal signal : compatibility.signals()) {
            sb.append("- [").append(signal.position()).append("] ").append(signal.detail()).append('\n');
        }
        if (compatibility.signals().isEmpty()) {
            sb.append("- 눈에 띄는 합도 충도 없다 (서로 간섭이 적은 조합)\n");
        }

        sb.append("\n[요청] ").append(type.label()).append('\n');
        sb.append("- 관점: ").append(type.focus()).append('\n');
        sb.append("- sections 5개는 이 주제로 구성해주세요: ")
                .append(String.join(" / ", type.sectionHints())).append('\n');

        return sb.toString();
    }

    private String callName(String nickname) {
        return nickname == null || nickname.isBlank() ? "고객님" : nickname;
    }

    private void appendBirth(StringBuilder sb, FourPillars p) {
        sb.append("- 생년월일: ").append(p.adjustedBirth().toLocalDate().format(DATE)).append(" (양력)\n");
        sb.append("- 출생시각: ").append(p.hourKnown()
                        ? p.adjustedBirth().toLocalTime().format(TIME) + " (진태양시 보정 반영)"
                        : "모름 — 시주를 뺀 여섯 글자로만 해석해주세요")
                .append('\n');
    }

    /** 사주팔자 + 오행 + 십성 분포 */
    private void appendChart(StringBuilder sb, FourPillars p) {
        sb.append("사주팔자:\n");
        appendPillar(sb, "연주", p.year(), p, false);
        appendPillar(sb, "월주", p.month(), p, false);
        appendPillar(sb, "일주", p.day(), p, true);
        if (p.hourKnown()) {
            appendPillar(sb, "시주", p.hour(), p, false);
        }
        sb.append("  · 띠: ").append(p.zodiac()).append("띠")
                .append(" / 월지 절기: ").append(p.monthTermName()).append('\n');

        StringJoiner elements = new StringJoiner(" · ");
        p.elementCounts().forEach((element, count) -> elements.add(element.korean() + " " + count));
        sb.append("  · 오행(").append(p.hourKnown() ? "여덟" : "여섯").append("자): ")
                .append(elements)
                .append(" / 가장 강한 오행 ").append(p.strongestElement().korean())
                .append(" / 없는 오행 ").append(p.missingElements().isEmpty()
                        ? "없음"
                        : String.join(",", p.missingElements().stream().map(Element::korean).toList()))
                .append('\n');

        StringJoiner tenGods = new StringJoiner(" · ");
        p.tenGodGroupCounts().forEach((group, count) -> tenGods.add(group + " " + count));
        sb.append("  · 십성 분포(일간 제외): ").append(tenGods).append('\n');
        sb.append("  · 일간의 힘: ").append(p.bodyStrength())
                .append(p.hasMonthSupport() ? " (월지가 일간을 도움 = 득령)" : " (월지가 일간을 돕지 않음)")
                .append(" — 참고용 간이 판정\n");
    }

    private void appendPillar(StringBuilder sb, String position, Pillar pillar,
                              FourPillars p, boolean isDayPillar) {
        HeavenlyStem me = p.dayMaster();
        sb.append("  · ").append(position).append(": ").append(pillar.display()).append(" — 천간 ")
                .append(isDayPillar
                        ? "일간(본인), 오행 " + me.element().korean()
                        : TenGod.of(me, pillar.stem()).korean())
                .append(", 지지 ").append(TenGod.of(me, pillar.branch()).korean())
                .append(" (지장간 ")
                .append(String.join(",", pillar.branch().hiddenStems().stream()
                        .map(HeavenlyStem::korean).toList()))
                .append(")\n");
    }

    private void appendLuck(StringBuilder sb, FourPillars p) {
        sb.append("\n[대운] ").append(p.forwardLuck() ? "순행" : "역행")
                .append(", 대운수 ").append(p.luckStartAge()).append('\n');
        appendCurrentLuckLine(sb, p);

        sb.append("- 전체 흐름(십성은 일간 기준): ");
        StringJoiner cycles = new StringJoiner(", ");
        for (LuckCycle c : p.luckCycles()) {
            cycles.add(c.startAge() + "세 " + c.pillar().korean()
                    + "(" + TenGod.of(p.dayMaster(), c.pillar().stem()).korean() + ")");
        }
        sb.append(cycles).append('\n');

        sb.append("[세운] ").append(p.yearlyLuckYear()).append("년 ")
                .append(p.yearlyLuck().display())
                .append(" — 천간 십성 ").append(TenGod.of(p.dayMaster(), p.yearlyLuck().stem()).korean())
                .append(", 지지 십성 ").append(TenGod.of(p.dayMaster(), p.yearlyLuck().branch()).korean())
                .append('\n');
    }

    /**
     * 배우자 자리 — 남자는 재성(財星), 여자는 관성(官星)을 배우자로 본다.
     *
     * <p>"상대가 어떤 사람일까"를 AI가 지어내지 않도록, 배우자성이 사주 어느 자리에 앉았고
     * 오행이 무엇인지 계산해서 넘긴다. 나이대(연상/연하)는 그 자리가 위쪽이냐 아래쪽이냐로,
     * 직업의 결은 오행으로 읽는 게 통상적인 해석이다.
     */
    private void appendSpouseStar(StringBuilder sb, FourPillars p, Gender gender) {
        String spouseGroup = gender == Gender.MALE ? "재성" : "관성";
        sb.append("\n[배우자 자리] ")
                .append(gender == Gender.MALE ? "남자는 재성(財星)" : "여자는 관성(官星)")
                .append("을 배우자로 본다\n");

        sb.append("- 일지(배우자궁): ").append(p.day().branch().display())
                .append(' ').append(p.day().branch().element().korean())
                .append(" — 십성 ").append(TenGod.of(p.dayMaster(), p.day().branch()).korean())
                .append('\n');

        StringJoiner spots = new StringJoiner(", ");
        Set<Element> elements = EnumSet.noneOf(Element.class);
        for (Map.Entry<String, Pillar> entry : p.positionedPillars()) {
            String position = entry.getKey();
            Pillar pillar = entry.getValue();

            TenGod stemGod = TenGod.of(p.dayMaster(), pillar.stem());
            if (stemGod.group().equals(spouseGroup)) {
                spots.add(position + " 천간 " + stemGod.korean()
                        + "(" + pillar.stem().korean() + "·" + pillar.stem().element().korean() + ")");
                elements.add(pillar.stem().element());
            }
            TenGod branchGod = TenGod.of(p.dayMaster(), pillar.branch());
            if (branchGod.group().equals(spouseGroup)) {
                spots.add(position + " 지지 " + branchGod.korean()
                        + "(" + pillar.branch().korean() + "·" + pillar.branch().element().korean() + ")");
                elements.add(pillar.branch().element());
            }
        }

        sb.append("- ").append(spouseGroup).append("이 앉은 자리: ")
                .append(spots.length() == 0
                        ? "여덟 글자 안에는 없음 (대운·세운에서 들어올 때 인연이 생긴다)"
                        : spots.toString())
                .append('\n');
        if (!elements.isEmpty()) {
            sb.append("- ").append(spouseGroup).append("의 오행: ")
                    .append(String.join(", ", elements.stream().map(Element::korean).toList()))
                    .append('\n');
        }
        sb.append("- 연주·월주 쪽에 있으면 연상, 시주 쪽이면 연하로 보는 편이다\n");
    }

    /**
     * 연도별 세운 표.
     *
     * <p>"몇 년에 기회가 온다"를 물으면 AI가 연도를 지어내기 쉽다. 실제 간지를 미리 계산해 주고
     * 이 표 안에서만 고르게 한다. 만 16세부터 기준연도 +12년까지.
     */
    private void appendYearSeries(StringBuilder sb, FourPillars p) {
        int reference = p.yearlyLuckYear();
        int from = Math.max(p.adjustedBirth().getYear() + 16, reference - 12);
        int to = reference + 12;

        sb.append("\n[연도별 세운] 아래 연도와 간지만 쓰세요. 표에 없는 해를 지어내지 마세요.\n");
        for (int year = from; year <= to; year++) {
            Pillar pillar = SajuCalculator.yearPillarOf(year);
            sb.append("- ").append(year).append("년 ").append(pillar.korean())
                    .append(" (천간 ").append(TenGod.of(p.dayMaster(), pillar.stem()).korean())
                    .append(", 지지 ").append(TenGod.of(p.dayMaster(), pillar.branch()).korean())
                    .append(')');
            if (year < reference) sb.append(" — 지난 해");
            else if (year == reference) sb.append(" — 올해");
            sb.append('\n');
        }
    }

    /** 궁합에선 대운 전체까지는 필요 없고 현재 구간과 세운만 본다 */
    private void appendCurrentLuck(StringBuilder sb, FourPillars p) {
        appendCurrentLuckLine(sb, p);
        sb.append("  · ").append(p.yearlyLuckYear()).append("년 세운 ")
                .append(p.yearlyLuck().display()).append('\n');
    }

    private void appendCurrentLuckLine(StringBuilder sb, FourPillars p) {
        LuckCycle current = p.currentLuck();
        sb.append("  · 현재 대운: ").append(current == null
                        ? "아직 첫 대운 전 (" + p.luckStartAge() + "세부터 시작)"
                        : current.pillar().display() + " " + current.startAge() + "~" + current.endAge() + "세"
                          + " (" + TenGod.of(p.dayMaster(), current.pillar().stem()).korean() + " 대운)")
                .append('\n');
    }
}
