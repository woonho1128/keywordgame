package com.wordplay.mafia;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MafiaBotCodesTest {

    @Test
    void 발급_검증_1회소비() {
        MafiaBotCodes codes = new MafiaBotCodes();
        String c = codes.issue();
        assertThat(c).matches("[A-Z0-9]{4}-[A-Z0-9]{4}");

        assertThat(codes.isValid(c)).isTrue();      // 확인은 소비 안 함
        assertThat(codes.isValid(c)).isTrue();

        assertThat(codes.consume(c)).isTrue();       // 첫 소비 성공
        assertThat(codes.consume(c)).isFalse();      // 재사용 불가
        assertThat(codes.isValid(c)).isFalse();
    }

    @Test
    void 대소문자_구분자_무시하고_매칭() {
        MafiaBotCodes codes = new MafiaBotCodes();
        String c = codes.issue();                    // 예: ABCD-EFGH
        String messy = c.replace("-", "").toLowerCase();
        assertThat(codes.consume(messy)).isTrue();
    }

    @Test
    void 잘못된_코드는_무효() {
        MafiaBotCodes codes = new MafiaBotCodes();
        assertThat(codes.isValid("ZZZZ-ZZZZ")).isFalse();
        assertThat(codes.consume("없는코드")).isFalse();
    }
}
