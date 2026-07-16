package com.wordplay.yacht;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.yacht.dto.NewYachtRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** 야찌 방 관리. */
@Service
public class YachtRoomManager {

    private final RoomRegistry<YachtGame> reg = new RoomRegistry<>();

    public String create(String clientId, NewYachtRequest req) {
        YachtGame game = new YachtGame(clientId, req.nick());
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public YachtGame require(String code) {
        YachtGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public YachtGame find(String code) { return reg.find(code); }
    public List<RoomSummary> list() { return reg.list(); }
    public void resetAll() { reg.clear(); }
    public boolean closeRoom(String code) { return reg.remove(code); }
    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
