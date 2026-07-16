package com.wordplay.mojo;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.mojo.dto.NewMojoRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** 모죠 방 관리. */
@Service
public class MojoRoomManager {

    private final RoomRegistry<MojoGame> reg = new RoomRegistry<>("mojo");

    public String create(String clientId, NewMojoRequest req) {
        MojoGame game = new MojoGame(clientId, req.nick(), req.doublePile());
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public MojoGame require(String code) {
        MojoGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public MojoGame find(String code) { return reg.find(code); }
    public List<RoomSummary> list() { return reg.list(); }
    public void resetAll() { reg.clear(); }
    public boolean closeRoom(String code) { return reg.remove(code); }
    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
