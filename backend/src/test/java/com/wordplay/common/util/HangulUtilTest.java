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
        // 추측 음절 1 = 리 → ㄹ(CHO):S (정답 ㄹ은 JONG이라 다른 kind), ㅣ(JUNG):S
        assertThat(r.get(1).marks()).extracting(JamoMark::jamo)
                .containsExactly("ㄹ", "ㅣ");
        assertThat(r.get(1).marks()).extracting(JamoMark::mark)
                .containsExactly("S", "S");
    }
}
