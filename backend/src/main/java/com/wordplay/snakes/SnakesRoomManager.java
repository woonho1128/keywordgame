package com.wordplay.snakes;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.snakes.dto.NewSnakesRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** 뱀과 사다리 방 관리. */
@Service
public class SnakesRoomManager {

    private final RoomRegistry<SnakesGame> reg = new RoomRegistry<>("snakes");

    public String create(String clientId, NewSnakesRequest req) {
        SnakesGame game = new SnakesGame(clientId, req.nick(), req.turnSec());
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public SnakesGame require(String code) {
        SnakesGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public SnakesGame find(String code) { return reg.find(code); }
    public List<RoomSummary> list() { return reg.list(); }
    public void resetAll() { reg.clear(); }
    public boolean closeRoom(String code) { return reg.remove(code); }
    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
