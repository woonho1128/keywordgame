package com.wordplay.yut;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.yut.dto.NewYutRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** 윷놀이 방 관리. */
@Service
public class YutRoomManager {

    private final RoomRegistry<YutGame> reg = new RoomRegistry<>("yut");

    public String create(String clientId, NewYutRequest req) {
        YutGame game = new YutGame(clientId, req.nick(), req.teamMode(), req.backDo());
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public YutGame require(String code) {
        YutGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public YutGame find(String code) { return reg.find(code); }
    public List<RoomSummary> list() { return reg.list(); }
    public void resetAll() { reg.clear(); }
    public boolean closeRoom(String code) { return reg.remove(code); }
    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
