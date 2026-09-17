package com.wordplay.common.room;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoomRegistryTest {

    /** 항상 '진행중 · 1명'인 가짜 방. lastActiveMs는 계속 갱신돼 기존 IDLE 규칙으로는 안 지워진다. */
    static final class FakeGame implements RoomGame {
        @Override public String roomStatus() { return "PLAYING"; }
        @Override public int playerCount() { return 1; }
        @Override public String hostLabel() { return "호스트"; }
        @Override public boolean isEnded() { return false; }
        @Override public long lastActiveMs() { return System.currentTimeMillis(); }
        @Override public void leave(String clientId) { }
    }

    /** 레지스트리가 기록한 마지막 접근 시각을 과거로 밀어 '아무도 안 보는 방'을 재현. */
    @SuppressWarnings("unchecked")
    private void ageAccess(RoomRegistry<?> reg, String code, long ms) throws Exception {
        Field f = RoomRegistry.class.getDeclaredField("lastAccess");
        f.setAccessible(true);
        Map<String, Long> m = (Map<String, Long>) f.get(reg);
        m.put(code, System.currentTimeMillis() - ms);
    }

    @Test
    void 아무도_보지_않는_방은_3분_뒤_목록에서_사라진다() throws Exception {
        RoomRegistry<FakeGame> reg = new RoomRegistry<>("faketest");
        String code = reg.add(new FakeGame());
        assertThat(reg.list()).hasSize(1);

        ageAccess(reg, code, 4 * 60_000L); // 4분간 요청 없음
        assertThat(reg.list()).isEmpty();
        assertThat(reg.find(code)).isNull();
        assertThat(RoomRegistry.resolveGame(code)).isNull(); // 전역 인덱스도 정리
    }

    @Test
    void 폴링이_계속되면_방은_유지된다() throws Exception {
        RoomRegistry<FakeGame> reg = new RoomRegistry<>("faketest2");
        String code = reg.add(new FakeGame());

        ageAccess(reg, code, 4 * 60_000L);
        assertThat(reg.find(code)).isNotNull(); // find가 접근 시각을 갱신(=화면이 열려 폴링 중)
        assertThat(reg.list()).hasSize(1);      // 그래서 살아 있다
    }
}
