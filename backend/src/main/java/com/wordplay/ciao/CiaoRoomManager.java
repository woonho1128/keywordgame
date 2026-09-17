package com.wordplay.ciao;

import com.wordplay.ciao.dto.NewCiaoRequest;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

/** 차오차오 방 관리. */
@Service
public class CiaoRoomManager {

    private final RoomRegistry<CiaoGame> reg = new RoomRegistry<>("ciao");

    public String create(String clientId, NewCiaoRequest req) {
        CiaoGame game = new CiaoGame(clientId, req.nick(), req.challengeSec());
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public CiaoGame require(String code) {
        CiaoGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public CiaoGame find(String code) { return reg.find(code); }
    public List<RoomSummary> list() { return reg.list(); }
    public void resetAll() { reg.clear(); }
    public boolean closeRoom(String code) { return reg.remove(code); }
    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
