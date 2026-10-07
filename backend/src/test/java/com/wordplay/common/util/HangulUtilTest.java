package com.wordplay.common.util;

import com.wordplay.common.util.HangulUtil.JamoMark;
import com.wordplay.common.util.HangulUtil.Kind;
import com.wordplay.common.util.HangulUtil.SyllableResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HangulUtilTest {

    @Test
    void isHangulSyllable_정상() {
        assertThat(HangulUtil.isHangulSyllable('가')).isTrue();
        assertThat(HangulUtil.isHangulSyllable('힣')).isTrue();
        assertThat(HangulUtil.isHangulSyllable('a')).isFalse();
        assertThat(HangulUtil.isHangulSyllable('1')).isFalse();
    }

    @Test
    void decompose_사_단순() {
        List<HangulUtil.Jamo> r = HangulUtil.decompose('사');
        assertThat(r).hasSize(2);
        assertThat(r.get(0).jamo()).isEqualTo("ㅅ");
        assertThat(r.get(0).kind()).isEqualTo(Kind.CHO);
        assertThat(r.get(1).jamo()).isEqualTo("ㅏ");
        assertThat(r.get(1).kind()).isEqualTo(Kind.JUNG);
    }

    @Test
    void decompose_과_복합중성_분리() {
        List<HangulUtil.Jamo> r = HangulUtil.decompose('과');
        assertThat(r).hasSize(3);
        assertThat(r.get(0).jamo()).isEqualTo("ㄱ");
        assertThat(r.get(1).jamo()).isEqualTo("ㅗ");
        assertThat(r.get(2).jamo()).isEqualTo("ㅏ");
        assertThat(r.get(1).kind()).isEqualTo(Kind.JUNG);
        assertThat(r.get(2).kind()).isEqualTo(Kind.JUNG);
    }

    @Test
    void decompose_닭_복합종성_분리() {
        // 닭 = ㄷ + ㅏ + ㄺ (= ㄹ + ㄱ)
        List<HangulUtil.Jamo> r = HangulUtil.decompose('닭');
        assertThat(r).hasSize(4);
        assertThat(r.get(0).jamo()).isEqualTo("ㄷ");
        assertThat(r.get(1).jamo()).isEqualTo("ㅏ");
        assertThat(r.get(2).jamo()).isEqualTo("ㄹ");
        assertThat(r.get(3).jamo()).isEqualTo("ㄱ");
        assertThat(r.get(2).kind()).isEqualTo(Kind.JONG);
        assertThat(r.get(3).kind()).isEqualTo(Kind.JONG);
    }

    @Test
    void compareWords_사과_사과_전부H() {
        List<SyllableResult> r = HangulUtil.compareWords("사과", "사과");
        // 음절 0: 사 → ㅅ:H, ㅏ:H
        assertThat(r.get(0).marks()).extracting(JamoMark::mark)
                .containsExactly("H", "H");
        // 음절 1: 과 → ㄱ:H, ㅗ:H, ㅏ:H
        assertThat(r.get(1).marks()).extracting(JamoMark::mark)
                .containsExactly("H", "H", "H");
    }

    @Test
    void compareWords_jamoCountMismatch_throws() {
        // 자모 수 다르면 예외 (서비스 레이어에서 INVALID_WORD_LENGTH 변환)
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> HangulUtil.compareWords("과", "가")  // 3 vs 2
        );
    }

    @Test
    void compareWords_사과vs아사_중복자모() {
        // 정답 "사과" (ㅅㅏㄱㅗㅏ=5), 추측 "아사" (ㅇㅏㅅㅏ=4)... 4!=5 안 됨.
        // 같은 자모수로 다시: 정답 "사과" (5) vs 추측 "사가나" (ㅅㅏㄱㅏㄴㅏ=6)... 6!=5
        // 사림 = ㅅㅏㄹㅣㅁ = 5. OK.
        // 1단계: CHO ㅅ=ㅅ H, ㄱ vs ㄹ no. JUNG ㅏ=ㅏ H, ㅗ vs ㅣ no, ㅏ vs (없음).
        // Pool: CHO {ㄱ:1}, JUNG {ㅗ:1, ㅏ:1}, JONG {}.
        // 추측 ㄹ(CHO) → S, ㅣ(JUNG) → S, ㅁ(JONG) → S.
        List<SyllableResult> r = HangulUtil.compareWords("사과", "사림");
        assertThat(r.get(0).marks()).extracting(JamoMark::mark)
                .containsExactly("H", "H");           // 사 → ㅅ:H, ㅏ:H
        assertThat(r.get(1).marks()).extracting(JamoMark::mark)
                .containsExactly("S", "S", "S");      // 림 → 모두 S (ㄹ/ㅣ/ㅁ 정답에 없음)
    }

    @Test
    void compareWords_감vs강_종성차이() {
        // 정답 '감' (ㄱ+ㅏ+ㅁ), 추측 '강' (ㄱ+ㅏ+ㅇ)
        List<SyllableResult> r = HangulUtil.compareWords("감", "강");
        assertThat(r.get(0).marks()).extracting(JamoMark::jamo)
                .containsExactly("ㄱ", "ㅏ", "ㅇ");
        assertThat(r.get(0).marks()).extracting(JamoMark::mark)
                .containsExactly("H", "H", "S");
    }

    @Test
    void decompose_떼_쌍자음_이중모음_모두분해() {
        // 떼 = ㄸ + ㅔ
        // 새 규칙: ㄸ → ㄷㄷ, ㅔ → ㅓㅣ
        // 결과: ㄷ(CHO) + ㄷ(CHO) + ㅓ(JUNG) + ㅣ(JUNG)
        List<HangulUtil.Jamo> r = HangulUtil.decompose('떼');
        assertThat(r).hasSize(4);
        assertThat(r).extracting(HangulUtil.Jamo::jamo)
                .containsExactly("ㄷ", "ㄷ", "ㅓ", "ㅣ");
        assertThat(r).extracting(HangulUtil.Jamo::kind)
                .containsExactly(Kind.CHO, Kind.CHO, Kind.JUNG, Kind.JUNG);
    }

    @Test
    void decompose_봬_쌍받침_없음() {
        // 봬 = ㅂ + ㅙ (= ㅗ + ㅏ + ㅣ) → 4 jamos
        List<HangulUtil.Jamo> r = HangulUtil.decompose('봬');
        assertThat(r).extracting(HangulUtil.Jamo::jamo)
                .containsExactly("ㅂ", "ㅗ", "ㅏ", "ㅣ");
    }

    @Test
    void decompose_았_쌍받침() {
        // 았 = ㅇ + ㅏ + ㅆ → ㅇ + ㅏ + ㅅ + ㅅ (4 jamos)
        List<HangulUtil.Jamo> r = HangulUtil.decompose('았');
        assertThat(r).extracting(HangulUtil.Jamo::jamo)
                .containsExactly("ㅇ", "ㅏ", "ㅅ", "ㅅ");
    }

    @Test
    void decompose_애_이중모음() {
        // 애 = ㅇ + ㅐ → ㅇ + ㅏ + ㅣ (3 jamos)
        List<HangulUtil.Jamo> r = HangulUtil.decompose('애');
        assertThat(r).extracting(HangulUtil.Jamo::jamo)
                .containsExactly("ㅇ", "ㅏ", "ㅣ");
    }

    @Test
    void countSyllables_글자수() {
        assertThat(HangulUtil.countSyllables("마피아")).isEqualTo(3);
        assertThat(HangulUtil.countSyllables("매핌")).isEqualTo(2);   // 자모수 같아도 글자수 다름
        assertThat(HangulUtil.countSyllables("닭")).isEqualTo(1);
    }

    @Test
    void countJamos_complex() {
        assertThat(HangulUtil.countJamos("사과")).isEqualTo(5);   // ㅅㅏㄱㅗㅏ
        assertThat(HangulUtil.countJamos("떼")).isEqualTo(4);     // ㄷㄷㅓㅣ
        assertThat(HangulUtil.countJamos("닭")).isEqualTo(4);     // ㄷㅏㄹㄱ
        assertThat(HangulUtil.countJamos("아이시떼루")).isEqualTo(12);
    }

    @Test
    void compareWords_음절수다름_자모수같음() {
        // 정답 "닭" (1음절, ㄷㅏㄹㄱ=4자모) vs 추측 "다리" (2음절, ㄷㅏㄹㅣ=4자모)
        List<SyllableResult> r = HangulUtil.compareWords("닭", "다리");
        // 추측 음절 0 = 다 → ㄷ:H, ㅏ:H
        assertThat(r.get(0).marks()).extracting(JamoMark::jamo)
                .containsExactly("ㄷ", "ㅏ");
        assertThat(r.get(0).marks()).extracting(JamoMark::mark)
                .containsExactly("H", "H");
        // 추측 음절 1 = 리 → ㄹ:H (셋째 칸 ㄹ은 정답 셋째 칸 ㄹ과 같음 — 받침/초성 역할은 따지지 않음)
        //                    ㅣ:S
        assertThat(r.get(1).marks()).extracting(JamoMark::jamo)
                .containsExactly("ㄹ", "ㅣ");
        assertThat(r.get(1).marks()).extracting(JamoMark::mark)
                .containsExactly("H", "S");
    }

    @Test
    void compareWords_사과vs석수_같은칸이면_받침이어도_H() {
        // 정답 사과 = ㅅ ㅏ [ㄱ] ㅗ ㅏ, 추측 석수 = ㅅ ㅓ [ㄱ] ㅅ ㅜ → 셋째 칸 ㄱ은 같은 자리
        // (정답 ㄱ은 '과'의 초성, 추측 ㄱ은 '석'의 받침이지만 입력 칸 기준으로 같은 칸이다)
        List<SyllableResult> r = HangulUtil.compareWords("사과", "석수");
        assertThat(r.get(0).marks()).extracting(JamoMark::mark)
                .containsExactly("H", "S", "H");       // 석 → ㅅ:H, ㅓ:S, ㄱ:H
        assertThat(r.get(1).marks()).extracting(JamoMark::mark)
                .containsExactly("S", "S");            // 수 → ㅅ:S (정답 ㅅ은 이미 H), ㅜ:S
    }

    @Test
    void compareWords_자모줄이같은데단어가다르면_역할다른칸은M() {
        // 아까 = ㅇㅏㄱㄱㅏ (ㄲ 분해), 악가 = ㅇㅏㄱㄱㅏ → 자모 줄이 통째로 같지만 다른 단어
        // 셋째 칸 ㄱ이 정답에선 초성(까), 추측에선 받침(악) → 이 칸만 M으로 내려 전부 초록을 막는다
        List<SyllableResult> r = HangulUtil.compareWords("아까", "악가");
        assertThat(r.get(0).marks()).extracting(JamoMark::mark)
                .containsExactly("H", "H", "M");       // 악 → ㅇ:H, ㅏ:H, ㄱ:M
        assertThat(r.get(1).marks()).extracting(JamoMark::mark)
                .containsExactly("H", "H");            // 가 → ㄱ:H, ㅏ:H
    }

    @Test
    void compareWords_받침위치만다른오답은_전부H가_아님() {
        // 예전 규칙(kind별 p번째끼리 비교)에서는 아래 쌍이 모두 전부 H인데 오답이었다.
        String[][] pairs = {
                {"김치", "기침"}, {"사장", "상자"}, {"수술", "술수"},
                {"가방", "강바"}, {"친구", "치군"}, {"아까", "악가"}
        };
        for (String[] p : pairs) {
            List<String> marks = flatMarks(HangulUtil.compareWords(p[0], p[1]));
            assertThat(marks).as("정답 %s / 추측 %s", p[0], p[1])
                    .anyMatch(m -> !m.equals("H"))
                    .contains("M");                    // 옮겨진 자모는 M으로 알려준다
        }
    }

    @Test
    void compareWords_김치vs기침_다른자리의자모는M() {
        List<SyllableResult> r = HangulUtil.compareWords("김치", "기침");
        assertThat(r.get(0).marks()).extracting(JamoMark::mark)
                .containsExactly("H", "H");            // 기 → ㄱ:H, ㅣ:H
        assertThat(r.get(1).marks()).extracting(JamoMark::mark)
                .containsExactly("M", "M", "M");       // 침 → ㅊ, ㅣ, ㅁ 모두 다른 자리에 있음
    }

    @Test
    void compareWords_가방vs강아_다른음절의받침은H가아니라M() {
        // 정답 받침 ㅇ은 둘째 음절(방)에 있음 → 첫 음절 받침 자리의 ㅇ은 H가 아니라 M
        List<SyllableResult> r = HangulUtil.compareWords("가방", "강아");
        assertThat(r.get(0).marks()).extracting(JamoMark::mark)
                .containsExactly("H", "H", "M");       // 강 → ㄱ:H, ㅏ:H, ㅇ:M
        assertThat(r.get(1).marks()).extracting(JamoMark::mark)
                .containsExactly("S", "M");            // 아 → ㅇ:S (ㅇ은 하나뿐이고 이미 M으로 소비), ㅏ:M
    }

    @Test
    void compareWords_경기vs사과_초성받침구분없이_글자로M() {
        // 정답 사과의 ㄱ(초성)이 있으므로 추측 경의 초성 ㄱ은 다른 자리 → M
        // 같은 ㄱ이 하나 더 나오면(기) 정답 ㄱ은 하나뿐이라 S
        List<SyllableResult> r = HangulUtil.compareWords("사과", "경기");
        assertThat(r.get(0).marks()).extracting(JamoMark::mark)
                .containsExactly("M", "S", "S");       // 경 → ㄱ:M, ㅕ:S, ㅇ:S
        assertThat(r.get(1).marks()).extracting(JamoMark::mark)
                .containsExactly("S", "S");            // 기 → ㄱ:S, ㅣ:S
    }

    @Test
    void compareWords_가나vs나나_중복자모는_정답개수만큼만() {
        // 정답의 ㄴ은 하나 → 둘째 칸 ㄴ이 H로 소비하므로 첫 ㄴ은 S
        List<SyllableResult> r = HangulUtil.compareWords("가나", "나나");
        assertThat(r.get(0).marks()).extracting(JamoMark::mark)
                .containsExactly("S", "H");
        assertThat(r.get(1).marks()).extracting(JamoMark::mark)
                .containsExactly("H", "H");
    }

    private static List<String> flatMarks(List<SyllableResult> r) {
        return r.stream().flatMap(s -> s.marks().stream()).map(JamoMark::mark).toList();
    }
}
