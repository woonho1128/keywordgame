package com.wordplay.horserace.account;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PBKDF2(SHA-256) 기반 비밀번호 해시. JDK 내장만 사용(외부 의존성 없음).
 * 형식: pbkdf2$&lt;iter&gt;$&lt;saltB64&gt;$&lt;hashB64&gt;
 * 놀이용 계정 수준의 최소 보안 — 평문 저장/로깅 금지.
 */
public final class PasswordHasher {

    private static final int ITER = 120_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RNG = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] dk = pbkdf2(password, salt, ITER);
        return "pbkdf2$" + ITER + "$" + b64(salt) + "$" + b64(dk);
    }

    public static boolean verify(String password, String stored) {
        try {
            String[] p = stored.split("\\$");
            if (p.length != 4 || !"pbkdf2".equals(p[0])) return false;
            int iter = Integer.parseInt(p[1]);
            byte[] salt = unb64(p[2]);
            byte[] expected = unb64(p[3]);
            byte[] actual = pbkdf2(password, salt, iter);
            return constantTimeEquals(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iter) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iter, KEY_BITS);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("해시 계산 실패", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int r = 0;
        for (int i = 0; i < a.length; i++) r |= a[i] ^ b[i];
        return r == 0;
    }

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    private static byte[] unb64(String s) { return Base64.getDecoder().decode(s); }
}
