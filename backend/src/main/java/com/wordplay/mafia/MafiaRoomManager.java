package com.wordplay.mafia;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.mafia.dto.NewMafiaRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** 마피아 방 여러 개 관리(방 코드별 게임 인스턴스). */
@Service
public class MafiaRoomManager {

    private final RoomRegistry<MafiaService> reg = new RoomRegistry<>();

    /** 방 생성 + 방장 참가 → 새 방 코드 반환. */
    public String create(String clientId, NewMafiaRequest req) {
        MafiaService game = new MafiaService();
        game.newGame(clientId, req);
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    /** 방 조회(없으면 예외). */
    public MafiaService require(String code) {
        MafiaService g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    /** 방 조회(없으면 null). */
    public MafiaService find(String code) {
        return reg.find(code);
    }

    public List<RoomSummary> list() {
        return reg.list();
    }

    public void resetAll() {
        reg.clear();
    }
}
