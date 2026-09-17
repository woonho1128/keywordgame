package com.wordplay.spicy;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.spicy.dto.NewSpicyRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** 스파이시 방 관리. */
@Service
public class SpicyRoomManager {

    private final RoomRegistry<SpicyGame> reg = new RoomRegistry<>("spicy");

    public String create(String clientId, NewSpicyRequest req) {
        SpicyGame game = new SpicyGame(clientId, req.nick(), req.challengeSec(), req.handPenalty());
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public SpicyGame require(String code) {
        SpicyGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public SpicyGame find(String code) { return reg.find(code); }
    public List<RoomSummary> list() { return reg.list(); }
    public void resetAll() { reg.clear(); }
    public boolean closeRoom(String code) { return reg.remove(code); }
    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
