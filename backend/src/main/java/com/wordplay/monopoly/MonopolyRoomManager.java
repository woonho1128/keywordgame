package com.wordplay.monopoly;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.monopoly.dto.NewMonopolyRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** 부루마블 방 관리. */
@Service
public class MonopolyRoomManager {

    private final RoomRegistry<MonopolyGame> reg = new RoomRegistry<>("monopoly");

    public String create(String clientId, NewMonopolyRequest req) {
        MonopolyGame game = new MonopolyGame(clientId, req.nick(), req.teamMode());
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public MonopolyGame require(String code) {
        MonopolyGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public MonopolyGame find(String code) { return reg.find(code); }
    public List<RoomSummary> list() { return reg.list(); }
    public void resetAll() { reg.clear(); }
    public boolean closeRoom(String code) { return reg.remove(code); }
    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
