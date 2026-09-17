package com.wordplay.rummikub;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

/** 루미큐브 방 여러 개 관리. */
@Service
public class RummikubRoomManager {

    private final RoomRegistry<RummikubGame> reg = new RoomRegistry<>("rummikub");

    public String create(String clientId, String nick) {
        return create(clientId, nick, null);
    }

    public String create(String clientId, String nick, Integer turnSec) {
        RummikubGame game = new RummikubGame();
        game.newGame(clientId, nick, turnSec);
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public RummikubGame require(String code) {
        RummikubGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public RummikubGame find(String code) { return reg.find(code); }

    public List<RoomSummary> list() { return reg.list(); }

    public void resetAll() { reg.clear(); }

    public boolean closeRoom(String code) { return reg.remove(code); }

    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
