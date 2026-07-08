package com.wordplay.jobmafia;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.jobmafia.dto.NewJobMafiaRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** 직업 마피아 방 여러 개 관리. */
@Service
public class JobMafiaRoomManager {

    private final RoomRegistry<JobMafiaService> reg = new RoomRegistry<>();

    public String create(String clientId, NewJobMafiaRequest req) {
        JobMafiaService game = new JobMafiaService();
        game.newGame(clientId, req);
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public JobMafiaService require(String code) {
        JobMafiaService g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public JobMafiaService find(String code) { return reg.find(code); }

    public List<RoomSummary> list() { return reg.list(); }

    public void resetAll() { reg.clear(); }

    public boolean closeRoom(String code) { return reg.remove(code); }
}
