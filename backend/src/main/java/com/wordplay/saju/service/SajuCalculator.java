package com.wordplay.saju.service;

import com.wordplay.saju.domain.EarthlyBranch;
import com.wordplay.saju.domain.Element;
import com.wordplay.saju.domain.FourPillars;
import com.wordplay.saju.domain.Gender;
import com.wordplay.saju.domain.HeavenlyStem;
import com.wordplay.saju.domain.LuckCycle;
import com.wordplay.saju.domain.Pillar;
import com.wordplay.saju.domain.SolarTerms;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 만세력 계산기 — 생년월일시로 사주팔자와 대운을 뽑는다.
 *
 * <p>AI에게 계산을 맡기지 않고 여기서 결정론적으로 구하는 이유: LLM은 60갑자·절기 계산을
 * 자주 틀린다. AI는 이 결과를 "해석"만 한다.
 *
 * <h3>적용 규칙</h3>
 * <ul>
 *   <li><b>연주</b>: 1월 1일이 아니라 <b>입춘</b>이 해가 바뀌는 기준</li>
 *   <li><b>월주</b>: 달력 월이 아니라 <b>12절</b>(입춘·경칩·청명…) 기준. 월간은 년간에서 오호둔으로 유도</li>
 *   <li><b>일주</b>: 율리우스일 기반 60갑자 순환. 1949-10-01 = 갑자일로 검증</li>
 *   <li><b>시주</b>: 23시부터 다음 날 자시로 본다 (야자시/조자시는 구분하지 않음)</li>
 *   <li><b>진태양시</b>: 한국 표준시는 동경 135도 기준이라 서울(약 127도)은 약 30분 빠르다.
 *       기본값으로 -30분 보정하며 {@code app.saju.longitude-offset-minutes}로 조정 가능</li>
 * </ul>
 *
 * <p>입력은 <b>양력</b>만 받는다. 음력 생일은 사용자가 양력으로 변환해서 입력해야 한다.
 */
@Component
public class SajuCalculator {

    /** 절기 계산식의 정확도와 시간대 이력을 고려한 지원 범위 */
    public static final int MIN_BIRTH_YEAR = 1901;
    public static final int MAX_BIRTH_YEAR = 2099;

    /** 출생시각을 모를 때 연/월주 경계 판정에 쓰는 가정 시각 */
    private static final LocalTime ASSUMED_TIME = LocalTime.NOON;

    /** 60년 * 8 = 80년치 대운 */
    private static final int LUCK_CYCLE_COUNT = 8;

    /** 1970-01-01(epochDay 0)이 60갑자 17번(신사)이 되도록 맞춘 보정값 */
    private static final long DAY_PILLAR_OFFSET = 2440637L;

    private final int longitudeOffsetMinutes;

    public SajuCalculator(
            @Value("${app.saju.longitude-offset-minutes:-30}") int longitudeOffsetMinutes) {
        this.longitudeOffsetMinutes = longitudeOffsetMinutes;
    }

    /**
     * 사주팔자 계산.
     *
     * @param birthDate     양력 생년월일
     * @param birthTime     출생시각 (모르면 null)
     * @param gender        성별 (대운 방향 판정용)
     * @param referenceDate 세운·나이 기준일 (보통 오늘)
     */
    public FourPillars calculate(LocalDate birthDate, LocalTime birthTime,
                                 Gender gender, LocalDate referenceDate) {
        if (birthDate.getYear() < MIN_BIRTH_YEAR || birthDate.getYear() > MAX_BIRTH_YEAR) {
            throw new IllegalArgumentException(
                    "지원 범위는 " + MIN_BIRTH_YEAR + "년 ~ " + MAX_BIRTH_YEAR + "년입니다");
        }

        boolean hourKnown = birthTime != null;
        // 진태양시 보정은 시각을 알 때만 의미가 있다 (정오 가정에 보정을 얹으면 가짜 정밀도)
        LocalDateTime adjusted = hourKnown
                ? LocalDateTime.of(birthDate, birthTime).plusMinutes(longitudeOffsetMinutes)
                : LocalDateTime.of(birthDate, ASSUMED_TIME);

        int solarYear = solarYearOf(adjusted);
        Pillar yearPillar = yearPillarOf(solarYear);

        int termMonth = SolarTerms.termMonth(adjusted);
        Pillar monthPillar = monthPillar(yearPillar.stem(), termMonth);

        // 23시 이후 출생은 다음 날 자시로 본다
        LocalDate dayDate = adjusted.toLocalDate();
        if (hourKnown && adjusted.getHour() == 23) {
            dayDate = dayDate.plusDays(1);
        }
        Pillar dayPillar = dayPillar(dayDate);

        Pillar hourPillar = hourKnown ? hourPillar(dayPillar.stem(), adjusted.getHour()) : null;

        Map<Element, Integer> elementCounts =
                countElements(yearPillar, monthPillar, dayPillar, hourPillar);

        boolean forward = isForwardLuck(yearPillar.stem(), gender);
        int luckStartAge = luckStartAge(adjusted, forward);
        int koreanAge = referenceDate.getYear() - birthDate.getYear() + 1;
        List<LuckCycle> cycles = luckCycles(monthPillar, forward, luckStartAge, koreanAge);

        int yearlyLuckYear = referenceDate.getYear();
        Pillar yearlyLuck = yearPillarOf(
                solarYearOf(LocalDateTime.of(referenceDate, ASSUMED_TIME)));

        return new FourPillars(
                yearPillar, monthPillar, dayPillar, hourPillar,
                solarYear, SolarTerms.termName(termMonth),
                adjusted, hourKnown, elementCounts,
                forward, luckStartAge, cycles,
                yearlyLuck, yearlyLuckYear, koreanAge
        );
    }

    /** 입춘 기준 연도 — 입춘 전에 태어났으면 전년도 간지를 쓴다 */
    private int solarYearOf(LocalDateTime moment) {
        LocalDateTime ipchun = SolarTerms.termStart(moment.getYear(), 2);
        return moment.isBefore(ipchun) ? moment.getYear() - 1 : moment.getYear();
    }

    /** 연주 — 서기 4년이 갑자년. 세운(해당 연도 간지)도 같은 규칙이라 공개해 둔다 */
    public static Pillar yearPillarOf(int solarYear) {
        return Pillar.ofSexagenary(solarYear - 4);
    }

    /**
     * 월주 — 지지는 절기가 정하고(인월=입춘~), 천간은 년간에서 오호둔(五虎遁)으로 유도한다.
     * 갑·기년은 병인월, 을·경년은 무인월, 병·신년은 경인월, 정·임년은 임인월, 무·계년은 갑인월로 시작.
     */
    private Pillar monthPillar(HeavenlyStem yearStem, int termMonth) {
        int branchIndex = termMonth % 12;               // 1월(소한)=축 ... 12월(대설)=자
        int inMonthStem = (yearStem.ordinal() % 5) * 2 + 2;
        int offsetFromIn = Math.floorMod(branchIndex - EarthlyBranch.IN.ordinal(), 12);
        return new Pillar(
                HeavenlyStem.of(inMonthStem + offsetFromIn),
                EarthlyBranch.of(branchIndex)
        );
    }

    /** 일주 — 율리우스일 기반 60갑자 순환 */
    private Pillar dayPillar(LocalDate date) {
        return Pillar.ofSexagenary((int) Math.floorMod(date.toEpochDay() + DAY_PILLAR_OFFSET, 60L));
    }

    /**
     * 시주 — 지지는 2시간 단위(23~01시 자시), 천간은 일간에서 오자둔(五鼠遁)으로 유도한다.
     * 갑·기일은 갑자시, 을·경일은 병자시, 병·신일은 무자시, 정·임일은 경자시, 무·계일은 임자시로 시작.
     */
    private Pillar hourPillar(HeavenlyStem dayStem, int hour) {
        int branchIndex = ((hour + 1) / 2) % 12;
        int jaHourStem = (dayStem.ordinal() % 5) * 2;
        return new Pillar(
                HeavenlyStem.of(jaHourStem + branchIndex),
                EarthlyBranch.of(branchIndex)
        );
    }

    private Map<Element, Integer> countElements(Pillar... pillars) {
        Map<Element, Integer> counts = new EnumMap<>(Element.class);
        for (Element e : Element.values()) counts.put(e, 0);
        for (Pillar p : pillars) {
            if (p == null) continue;   // 시주를 모르는 경우
            counts.merge(p.stem().element(), 1, Integer::sum);
            counts.merge(p.branch().element(), 1, Integer::sum);
        }
        return counts;
    }

    /** 양남음녀는 순행, 음남양녀는 역행 */
    private boolean isForwardLuck(HeavenlyStem yearStem, Gender gender) {
        return yearStem.isYang() == (gender == Gender.MALE);
    }

    /**
     * 대운수 — 순행이면 다음 절입까지, 역행이면 지난 절입부터의 날수를 3으로 나눈다.
     * 나머지 1은 버리고 2는 올린다 (3일 = 1년).
     */
    private int luckStartAge(LocalDateTime birth, boolean forward) {
        long days = forward
                ? ChronoUnit.DAYS.between(birth, SolarTerms.nextTermStart(birth))
                : ChronoUnit.DAYS.between(SolarTerms.currentTermStart(birth), birth);

        int age = (int) (days / 3);
        if (days % 3 == 2) age++;
        return Math.max(age, 1);
    }

    /** 대운 — 월주에서 순행/역행으로 한 칸씩 옮겨가며 10년씩 */
    private List<LuckCycle> luckCycles(Pillar monthPillar, boolean forward,
                                       int startAge, int koreanAge) {
        int monthIndex = monthPillar.sexagenaryIndex();
        List<LuckCycle> cycles = new ArrayList<>(LUCK_CYCLE_COUNT);
        for (int i = 1; i <= LUCK_CYCLE_COUNT; i++) {
            int from = startAge + (i - 1) * 10;
            boolean current = koreanAge >= from && koreanAge < from + 10;
            cycles.add(new LuckCycle(
                    i,
                    from,
                    Pillar.ofSexagenary(forward ? monthIndex + i : monthIndex - i),
                    current
            ));
        }
        return cycles;
    }
}
