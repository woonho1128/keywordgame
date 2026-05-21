package com.wordplay.common.util;

import java.text.Normalizer;

/**
 * 단어 정규화 유틸 — NFC 정규화 + trim.
 * 입력에 대한 모든 비교/저장은 이 유틸을 거쳐야 한다.
 */
public class TextNormalizer {

    private TextNormalizer() {}

    public static String normalize(String input) {
        if (input == null) return null;
        return Normalizer.normalize(input.trim(), Normalizer.Form.NFC);
    }
}
