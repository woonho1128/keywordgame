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
    void compareWords_과vs가_복합중성_부분일치() {
        // 정답 '과' (ㄱ+ㅗ+ㅏ), 추측 '가' (ㄱ+ㅏ)
        // → ㄱ:H, ㅏ는 정답 ㅘ의 ㅏ와 매칭 → M
        List<SyllableResult> r = HangulUtil.compareWords("과", "가");
        assertThat(r.get(0).marks()).extracting(JamoMark::jamo)
                .containsExactly("ㄱ", "ㅏ");
        assertThat(r.get(0).marks()).extracting(JamoMark::mark)
                .containsExactly("H", "M");
    }

    @Test
    void compareWords_가vs과_역방향() {
        // 정답 '가' (ㄱ+ㅏ), 추측 '과' (ㄱ+ㅗ+ㅏ)
        // → ㄱ:H, ㅗ:S (정답에 없음), ㅏ:M (정답에 있지만 위치는 다름... 아니, 위치 0이 ㅏ인데 추측 ㅏ는 위치 1)
        // 사실 1단계 hit는 position-by-position. 정답 jung[0]=ㅏ, 추측 jung[0]=ㅗ → 미스. 추측 jung[1]=ㅏ vs 정답 jung[1]=없음 → 미스.
        // 2단계: 정답 pool에 ㅏ 1개. 추측 ㅗ→pool에 없음 → S. 추측 ㅏ→pool에 있음 → M.
        List<SyllableResult> r = HangulUtil.compareWords("가", "과");
        assertThat(r.get(0).marks()).extracting(JamoMark::jamo)
                .containsExactly("ㄱ", "ㅗ", "ㅏ");
        assertThat(r.get(0).marks()).extracting(JamoMark::mark)
                .containsExactly("H", "S", "M");
    }

    @Test
    void compareWords_사과_사사_중복자모표준() {
        // 정답 '사과' (ㅅ+ㅏ+ㄱ+ㅗ+ㅏ), 추측 '사사' (ㅅ+ㅏ+ㅅ+ㅏ)
        // 음절 0: 사=사 → ㅅ:H, ㅏ:H
        // 음절 1: 정답 과=ㄱ+ㅗ+ㅏ, 추측 사=ㅅ+ㅏ
        //   1단계: jung[0] ㅗ vs ㅏ → 미스
        //   2단계: pool[CHO]={ㄱ:1}, pool[JUNG]={ㅗ:1, ㅏ:1}
        //     ㅅ(CHO) → pool에 없음 → S
        //     ㅏ(JUNG) → pool에 있음 → M
        List<SyllableResult> r = HangulUtil.compareWords("사과", "사사");
        assertThat(r.get(1).marks()).extracting(JamoMark::jamo)
                .containsExactly("ㅅ", "ㅏ");
        assertThat(r.get(1).marks()).extracting(JamoMark::mark)
                .containsExactly("S", "M");
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
}
