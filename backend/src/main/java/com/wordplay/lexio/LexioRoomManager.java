package com.wordplay.lexio;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.lexio.dto.NewLexioRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** 렉시오 방 관리(방 코드별 게임 인스턴스). */
@Service
public class LexioRoomManager {

    private final RoomRegistry<LexioGame> reg = new RoomRegistry<>();

    public String create(String clientId, NewLexioRequest req) {
        LexioGame game = new LexioGame();
        game.newGame(clientId, req.nick(), req.theme(), req.scoreMode(), req.turnSec());
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public LexioGame require(String code) {
        LexioGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public LexioGame find(String code) { return reg.find(code); }
    public List<RoomSummary> list() { return reg.list(); }
    public void resetAll() { reg.clear(); }
    public boolean closeRoom(String code) { return reg.remove(code); }
    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
