package com.wordplay.avalon;

import com.wordplay.avalon.dto.NewAvalonRequest;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

/** 아발론 방 여러 개 관리. */
@Service
public class AvalonRoomManager {

    private final RoomRegistry<AvalonService> reg = new RoomRegistry<>();

    public String create(String clientId, NewAvalonRequest req) {
        AvalonService game = new AvalonService();
        game.newGame(clientId, req);
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public AvalonService require(String code) {
        AvalonService g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public AvalonService find(String code) { return reg.find(code); }

    public List<RoomSummary> list() { return reg.list(); }

    public void resetAll() { reg.clear(); }

    public boolean closeRoom(String code) { return reg.remove(code); }

    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
