package com.wordplay.common.util;

import com.aventrix.jnanoid.jnanoid.NanoIdUtils;

import java.security.SecureRandom;

/**
 * 게임 ID 생성기 — URL-safe nanoid.
 * 기본 8자리, 충돌 확률 매우 낮음 (~64^8 = 281조 가지).
 */
public class NanoIdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private NanoIdGenerator() {}

    public static String generate(int length) {
        return NanoIdUtils.randomNanoId(RANDOM, ALPHABET, length);
    }
}
