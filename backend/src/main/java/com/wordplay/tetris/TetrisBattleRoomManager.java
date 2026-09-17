package com.wordplay.tetris;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.tetris.dto.NewBattleRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 테트리스 배틀 방 관리. */
@Service
@RequiredArgsConstructor
public class TetrisBattleRoomManager {

    private final RoomRegistry<TetrisBattleGame> reg = new RoomRegistry<>("tetris-battle");
    private final TetrisBattleRankService rankService;

    public String create(String clientId, NewBattleRequest req) {
        TetrisBattleGame game = new TetrisBattleGame(clientId, req.nick(), req.format());
        game.setRankService(rankService);
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public TetrisBattleGame require(String code) {
        TetrisBattleGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public TetrisBattleGame find(String code) { return reg.find(code); }
    public List<RoomSummary> list() { return reg.list(); }
    public void resetAll() { reg.clear(); }
    public boolean closeRoom(String code) { return reg.remove(code); }
    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
