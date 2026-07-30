package com.wordplay.sherlock;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.sherlock.dto.NewSherlockRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** 셜록13 방 관리. */
@Service
public class SherlockRoomManager {

    private final RoomRegistry<SherlockGame> reg = new RoomRegistry<>("sherlock");

    public String create(String clientId, NewSherlockRequest req) {
        SherlockGame game = new SherlockGame(clientId, req.nick(), req.turnSec());
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public SherlockGame require(String code) {
        SherlockGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public SherlockGame find(String code) { return reg.find(code); }
    public List<RoomSummary> list() { return reg.list(); }
    public void resetAll() { reg.clear(); }
    public boolean closeRoom(String code) { return reg.remove(code); }
    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
