package com.wordplay.common.util;

import com.wordplay.common.util.HangulUtil.SyllableResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HangulUtilTest {

    @Test
    void decompose_사() {
        int[] r = HangulUtil.decompose('사');
        // ㅅ=9, ㅏ=0, 종성없음=0
        assertThat(r).containsExactly(9, 0, 0);
    }

    @Test
    void isHangulSyllable_정상() {
        assertThat(HangulUtil.isHangulSyllable('가')).isTrue();
        assertThat(HangulUtil.isHangulSyllable('힣')).isTrue();
        assertThat(HangulUtil.isHangulSyllable('a')).isFalse();
        assertThat(HangulUtil.isHangulSyllable('1')).isFalse();
    }

    @Test
    void compareWords_정답동일() {
        List<SyllableResult> r = HangulUtil.compareWords("사과", "사과");
        assertThat(r.get(0).cho()).isEqualTo("H");
        assertThat(r.get(0).jung()).isEqualTo("H");
        assertThat(r.get(1).cho()).isEqualTo("H");
        assertThat(r.get(1).jung()).isEqualTo("H");
    }

    /** 중복 자모 케이스 — Wordle 표준: 정답에 ㅅ 1개, 추측에 ㅅ 2개 → 두 번째는 S */
    @Test
    void compareWords_중복자모_Wordle표준() {
        List<SyllableResult> r = HangulUtil.compareWords("사과", "사사");
        // 음절 1: 사=사 → 초H 중H
        assertThat(r.get(0).cho()).isEqualTo("H");
        assertThat(r.get(0).jung()).isEqualTo("H");
        // 음절 2: 사 vs 과 → ㅅ은 풀에 없음(이미 H에서 소진) → S
        assertThat(r.get(1).cho()).isEqualTo("S");
        // 중성 ㅏ도 1단계에서 H로 매칭됐으므로 풀에 없음 → S
        assertThat(r.get(1).jung()).isEqualTo("S");
    }

    @Test
    void compareWords_Move자모() {
        // 정답 "사과", 추측 "가사" → 음절1 초성 ㄱ(과의 ㄱ과 매칭 → M), 음절2 초성 ㅅ(사의 ㅅ과 매칭 → M)
        List<SyllableResult> r = HangulUtil.compareWords("사과", "가사");
        assertThat(r.get(0).cho()).isEqualTo("M");  // ㄱ → 정답 둘째 위치에 ㄱ 있음
        assertThat(r.get(1).cho()).isEqualTo("M");  // ㅅ → 정답 첫째 위치에 ㅅ 있음
    }

    @Test
    void compareWords_종성_null() {
        List<SyllableResult> r = HangulUtil.compareWords("사과", "사과");
        assertThat(r.get(0).jong()).isNull();  // '사'에 종성 없음
        assertThat(r.get(1).jong()).isNull();  // '과'에 종성 없음
    }

    @Test
    void compareWords_종성있음() {
        // "감" 분해: ㄱ + ㅏ + ㅁ
        // "강" 분해: ㄱ + ㅏ + ㅇ
        List<SyllableResult> r = HangulUtil.compareWords("감", "강");
        assertThat(r.get(0).cho()).isEqualTo("H");
        assertThat(r.get(0).jung()).isEqualTo("H");
        assertThat(r.get(0).jong()).isEqualTo("S");  // ㅇ은 정답에 없음
    }
}
