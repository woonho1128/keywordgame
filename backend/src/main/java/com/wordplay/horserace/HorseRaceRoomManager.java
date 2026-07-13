package com.wordplay.horserace;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.horserace.dto.NewHorseRaceRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** 경마 방 관리(방 코드별 게임 인스턴스). */
@Service
public class HorseRaceRoomManager {

    private final RoomRegistry<HorseRaceGame> reg = new RoomRegistry<>();

    public String create(String clientId, NewHorseRaceRequest req, Long accountId, long accountBalance) {
        HorseRaceGame game = new HorseRaceGame();
        game.newGame(clientId, req.nick(), accountId, accountBalance, req.raceType(), req.oddsMode(),
                req.buyIn(), req.betSec(), req.horseCount(), req.autoEndRounds());
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public HorseRaceGame require(String code) {
        HorseRaceGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public HorseRaceGame find(String code) { return reg.find(code); }
    public List<RoomSummary> list() { return reg.list(); }
    public void resetAll() { reg.clear(); }
    public boolean closeRoom(String code) { return reg.remove(code); }
    public void leave(String code, String clientId) { reg.leave(code, clientId); }
}
