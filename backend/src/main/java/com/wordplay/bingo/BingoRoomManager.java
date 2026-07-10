package com.wordplay.bingo;

import com.wordplay.bingo.dto.NewBingoRequest;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BingoRoomManager {

    private final RoomRegistry<BingoGame> reg = new RoomRegistry<>();

    public String create(String clientId, NewBingoRequest req) {
        BingoGame game = new BingoGame();
        game.newGame(clientId, req.nick(), req.size(), req.target(), req.mode());
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public BingoGame require(String code) {
        BingoGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public BingoGame find(String code) { return reg.find(code); }

    public List<RoomSummary> list() { return reg.list(); }

    public void resetAll() { reg.clear(); }

    public boolean closeRoom(String code) { return reg.remove(code); }

    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
