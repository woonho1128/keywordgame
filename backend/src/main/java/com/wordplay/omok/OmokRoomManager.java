package com.wordplay.omok;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.omok.dto.NewOmokRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** 오목 방 관리. */
@Service
public class OmokRoomManager {

    private final RoomRegistry<OmokGame> reg = new RoomRegistry<>("omok");

    public String create(String clientId, NewOmokRequest req) {
        OmokGame game = new OmokGame();
        game.newGame(clientId, req.nick(), req.hostColor(), req.rule());
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public OmokGame require(String code) {
        OmokGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public OmokGame find(String code) { return reg.find(code); }
    public List<RoomSummary> list() { return reg.list(); }
    public void resetAll() { reg.clear(); }
    public boolean closeRoom(String code) { return reg.remove(code); }
    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
