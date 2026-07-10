package com.wordplay.othello;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.othello.dto.NewOthelloRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** 오델로 방 관리(방 코드별 게임 인스턴스). */
@Service
public class OthelloRoomManager {

    private final RoomRegistry<OthelloGame> reg = new RoomRegistry<>();

    public String create(String clientId, NewOthelloRequest req) {
        OthelloGame game = new OthelloGame();
        game.newGame(clientId, req.nick(), req.hostColor(), req.turnSec());
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public OthelloGame require(String code) {
        OthelloGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public OthelloGame find(String code) { return reg.find(code); }
    public List<RoomSummary> list() { return reg.list(); }
    public void resetAll() { reg.clear(); }
    public boolean closeRoom(String code) { return reg.remove(code); }
    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
