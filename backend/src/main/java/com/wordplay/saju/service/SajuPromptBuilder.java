package com.wordplay.saju.service;

import com.wordplay.saju.domain.FourPillars;
import com.wordplay.saju.domain.Gender;
import com.wordplay.saju.domain.LuckCycle;
import com.wordplay.saju.domain.Pillar;
import com.wordplay.saju.domain.SajuType;
import com.wordplay.saju.domain.TenGod;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.StringJoiner;

/**
 * AI 프롬프트 조립기.
 *
 * <p>핵심 전제: 사주팔자·대운·세운은 {@link SajuCalculator}가 이미 정확히 계산했다.
 * AI는 계산하지 않고 "해석"만 한다. 그래서 프롬프트에 계산 결과를 사실로 못박아 넣는다.
 */
@Component
public class SajuPromptBuilder {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static final String SYSTEM_PROMPT = """
            당신은 사주명리를 오래 공부한 상담가입니다. 한국어로만 답합니다.

            지켜야 할 원칙:
            1. 사주팔자, 오행 분포, 십성, 대운, 세운은 이미 정확하게 계산되어 주어집니다.
               절대 다시 계산하거나 다른 간지를 지어내지 마세요. 주어진 값만 근거로 씁니다.
            2. 해석에는 근거를 드러내세요. "일간 OO이 ~", "월지 OO이 ~", "재성이 강해서 ~" 처럼
               사주 용어를 한 번씩 짚어주되, 처음 보는 사람도 이해할 수 있게 풀어서 설명합니다.
            3. 따뜻하고 구체적으로 씁니다. 겁을 주거나 불행을 단정하지 않습니다.
               부정적인 부분은 "이렇게 하면 낫다"는 대안과 함께 말합니다.
            4. 질병 진단, 투자 권유, 법률 판단은 확정적으로 말하지 않습니다.
               건강은 생활 습관 수준으로, 재물은 성향 수준으로만 이야기합니다.
            5. 어디에나 들어맞는 뻔한 문장 대신, 이 사주에서만 나올 수 있는 이야기를 씁니다.
            6. 존댓말로 쓰되 상담하듯 편안한 문장을 씁니다.

            출력은 아래 JSON 하나만 내보냅니다. 코드블록, 설명, 인사말을 붙이지 마세요.
            {
              "headline": "한 줄 총평 (20자 이내)",
              "summary": "전체 요약 (3~4문장)",
              "sections": [
                { "title": "소제목 (12자 이내)", "body": "본문 (3~5문장)" }
              ],
              "keywords": ["핵심 키워드 (각 6자 이내)"],
              "lucky": {
                "color": "행운의 색",
                "number": "행운의 숫자",
                "direction": "도움이 되는 방향",
                "item": "지니면 좋은 것"
              },
              "advice": "지금 바로 실천할 조언 (2~3문장)",
              "score": 0에서 100 사이 정수
            }
            sections 는 4개, keywords 는 4개로 맞춰주세요.
            """;

    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    /** 계산된 사주를 사실 목록으로 정리해 사용자 프롬프트를 만든다 */
    public String userPrompt(SajuType type, String nickname, Gender gender, FourPillars p) {
        StringBuilder sb = new StringBuilder();

        sb.append("[상담 대상]\n");
        sb.append("- 호칭: ").append(nickname == null || nickname.isBlank() ? "고객님" : nickname).append('\n');
        sb.append("- 성별: ").append(gender.korean()).append('\n');
        sb.append("- 나이: 세는나이 ").append(p.koreanAge()).append("세\n");
        sb.append("- 생년월일: ").append(p.adjustedBirth().toLocalDate().format(DATE)).append(" (양력)\n");
        sb.append("- 출생시각: ").append(p.hourKnown()
                        ? p.adjustedBirth().toLocalTime().format(TIME) + " (진태양시 보정 반영)"
                        : "모름 — 시주를 뺀 여섯 글자로만 해석해주세요")
                .append('\n');

        sb.append("\n[사주팔자]\n");
        appendPillar(sb, "연주", p.year(), p);
        appendPillar(sb, "월주", p.month(), p);
        sb.append("- 일주: ").append(p.day().display())
                .append(" — 일간 ").append(p.dayMaster().display())
                .append(", 오행 ").append(p.dayMaster().element().korean())
                .append(" = 본인 자신")
                .append(", 일지 십성 ").append(TenGod.of(p.dayMaster(), p.day().branch()).korean())
                .append('\n');
        if (p.hourKnown()) {
            appendPillar(sb, "시주", p.hour(), p);
        }
        sb.append("- 띠: ").append(p.zodiac()).append("띠\n");
        sb.append("- 월지 기준 절기: ").append(p.monthTermName()).append('\n');

        sb.append("\n[오행 분포] (").append(p.hourKnown() ? "여덟" : "여섯").append(" 글자 기준)\n- ");
        StringJoiner elements = new StringJoiner(" · ");
        p.elementCounts().forEach((element, count) -> elements.add(element.korean() + " " + count));
        sb.append(elements).append('\n');
        sb.append("- 가장 강한 오행: ").append(p.strongestElement().korean()).append('\n');
        sb.append("- 사주에 없는 오행: ").append(p.missingElements().isEmpty()
                ? "없음"
                : String.join(", ", p.missingElements().stream().map(e -> e.korean()).toList())).append('\n');

        sb.append("\n[대운] ").append(p.forwardLuck() ? "순행" : "역행")
                .append(", 대운수 ").append(p.luckStartAge()).append('\n');
        LuckCycle current = p.currentLuck();
        sb.append("- 현재 대운: ").append(current == null
                ? "아직 첫 대운 전 (" + p.luckStartAge() + "세부터 시작)"
                : current.pillar().display() + " " + current.startAge() + "~" + current.endAge() + "세")
                .append('\n');
        sb.append("- 전체 흐름: ");
        StringJoiner cycles = new StringJoiner(", ");
        for (LuckCycle c : p.luckCycles()) {
            cycles.add(c.startAge() + "세 " + c.pillar().korean());
        }
        sb.append(cycles).append('\n');

        sb.append("\n[세운] ").append(p.yearlyLuckYear()).append("년 ")
                .append(p.yearlyLuck().display()).append('\n');

        sb.append("\n[요청] ").append(type.label()).append("\n");
        sb.append("- 관점: ").append(type.focus()).append('\n');
        sb.append("- 이런 주제로 sections 4개를 구성해주세요: ")
                .append(String.join(" / ", type.sectionHints())).append('\n');
        sb.append("- score 는 이 주제(").append(type.label())
                .append(")에 한정한 운세 점수입니다.\n");

        return sb.toString();
    }

    private void appendPillar(StringBuilder sb, String position, Pillar pillar, FourPillars p) {
        sb.append("- ").append(position).append(": ").append(pillar.display())
                .append(" — 천간 십성 ").append(TenGod.of(p.dayMaster(), pillar.stem()).korean())
                .append(", 지지 십성 ").append(TenGod.of(p.dayMaster(), pillar.branch()).korean())
                .append('\n');
    }
}
