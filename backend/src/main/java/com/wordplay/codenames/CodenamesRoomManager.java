package com.wordplay.codenames;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

/** 코드네임 방 여러 개 관리. */
@Service
public class CodenamesRoomManager {

    private final RoomRegistry<CodenamesGame> reg = new RoomRegistry<>();

    public String create(String clientId, String nick) {
        CodenamesGame game = new CodenamesGame();
        game.newGame(clientId, nick);
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public CodenamesGame require(String code) {
        CodenamesGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public CodenamesGame find(String code) { return reg.find(code); }

    public List<RoomSummary> list() { return reg.list(); }

    public void resetAll() { reg.clear(); }
}
