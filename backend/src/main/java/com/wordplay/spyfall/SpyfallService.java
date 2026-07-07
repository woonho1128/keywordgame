package com.wordplay.spyfall;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.spyfall.dto.SpyfallStateResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 스파이폴 공유방 로직. 단일 전역 게임을 인메모리로 관리한다.
 * (친구용 캐주얼 도구 — DB 미사용. 백엔드 재시작 시 진행 중 판은 초기화됨.)
 *
 * 흐름:
 *  - newGame: 새로고침 시 인원수/스파이 수를 받아 장소·좌석별 역할을 배정하고 좌석 초기화.
 *  - claim: 각 기기(clientId)가 버튼을 누르면 남은 좌석을 순서대로 배정. 이미 잡았으면 그대로 반환.
 *  - state: 재접속 시 기존 좌석/역할을 그대로 반환.
 */
@Service
public class SpyfallService {

    /** 좌석 하나의 배정 결과. */
    private record Seat(boolean isSpy, String location, String role) {}

    private long round = 0;
    private int playerCount = 0;
    private int spyCount = 0;
    private List<Seat> seats = List.of();          // seat index 0..playerCount-1
    private final Map<String, Integer> clientSeats = new HashMap<>(); // clientId -> seat index
    private int nextSeat = 0;

    /** 새 판 생성. */
    public synchronized SpyfallStateResponse newGame(int playerCount, int spyCount) {
        if (playerCount < 3 || playerCount > 12) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "인원수는 3~12명이어야 합니다");
        }
        if (spyCount < 1 || spyCount >= playerCount) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "스파이 수는 1명 이상, 인원수보다 적어야 합니다");
        }

        SpyfallLocations.Location loc =
                SpyfallLocations.ALL.get(ThreadLocalRandom.current().nextInt(SpyfallLocations.ALL.size()));

        // 비-스파이 좌석에 배정할 역할(겹치지 않게).
        List<String> rolePool = new ArrayList<>(loc.roles());
        Collections.shuffle(rolePool);

        List<Seat> newSeats = new ArrayList<>(playerCount);
        int nonSpy = playerCount - spyCount;
        for (int i = 0; i < spyCount; i++) {
            newSeats.add(new Seat(true, null, null));
        }
        for (int i = 0; i < nonSpy; i++) {
            newSeats.add(new Seat(false, loc.name(), rolePool.get(i % rolePool.size())));
        }
        Collections.shuffle(newSeats); // 좌석 순서 섞기

        this.round++;
        this.playerCount = playerCount;
        this.spyCount = spyCount;
        this.seats = newSeats;
        this.clientSeats.clear();
        this.nextSeat = 0;

        // 판만 만들고 좌석은 각자 버튼 누를 때 배정 → NOT_JOINED 상태로 반환.
        return statusOnly("NOT_JOINED");
    }

    /** 버튼 클릭 시: 좌석을 잡거나 기존 좌석을 반환. */
    public synchronized SpyfallStateResponse claim(String clientId) {
        if (round == 0) {
            return SpyfallStateResponse.notStarted();
        }
        Integer existing = clientSeats.get(clientId);
        if (existing != null) {
            return seatResponse(existing);
        }
        if (nextSeat >= playerCount) {
            return statusOnly("FULL");
        }
        int seatIdx = nextSeat++;
        clientSeats.put(clientId, seatIdx);
        return seatResponse(seatIdx);
    }

    /** 재접속/조회: 좌석을 새로 잡지 않고 현재 상태만 반환. */
    public synchronized SpyfallStateResponse state(String clientId) {
        if (round == 0) {
            return SpyfallStateResponse.notStarted();
        }
        Integer existing = clientSeats.get(clientId);
        if (existing != null) {
            return seatResponse(existing);
        }
        return statusOnly(nextSeat >= playerCount ? "FULL" : "NOT_JOINED");
    }

    private SpyfallStateResponse seatResponse(int seatIdx) {
        Seat s = seats.get(seatIdx);
        return new SpyfallStateResponse(
                "OK", round, seatIdx + 1,
                s.isSpy(), s.location(), s.role(),
                playerCount, spyCount, clientSeats.size());
    }

    private SpyfallStateResponse statusOnly(String status) {
        return new SpyfallStateResponse(
                status, round, 0, false, null, null,
                playerCount, spyCount, clientSeats.size());
    }
}
