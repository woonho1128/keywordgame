package com.wordplay.drawgame;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.drawgame.dto.NewDrawGameRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** 그림 게임 방 관리. */
@Service
public class DrawGameRoomManager {

    private final RoomRegistry<DrawGame> reg = new RoomRegistry<>();

    public String create(String clientId, NewDrawGameRequest req) {
        DrawGame game = new DrawGame();
        game.newGame(clientId, req.nick(), req.mode(), req.topicMode(), req.writeSec(), req.drawSec(), req.roundSec());
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public DrawGame require(String code) {
        DrawGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public DrawGame find(String code) { return reg.find(code); }

    public List<RoomSummary> list() { return reg.list(); }

    public void resetAll() { reg.clear(); }

    public boolean closeRoom(String code) { return reg.remove(code); }

    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
