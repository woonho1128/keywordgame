package com.wordplay.quiz;

import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.common.room.RoomRegistry;
import com.wordplay.quiz.dto.NewQuizRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** 상식 퀴즈 방 관리. */
@Service
public class QuizRoomManager {

    private final RoomRegistry<QuizGame> reg = new RoomRegistry<>("quiz");
    private final QuizBank bank;

    public QuizRoomManager(QuizBank bank) { this.bank = bank; }

    public String create(String clientId, NewQuizRequest req) {
        QuizGame game = new QuizGame(clientId, req.nick(), req.level(), req.rounds(), req.questionSec(), bank);
        try {
            return reg.add(game);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    public QuizGame require(String code) {
        QuizGame g = reg.find(code);
        if (g == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "방을 찾을 수 없습니다");
        return g;
    }

    public QuizGame find(String code) { return reg.find(code); }
    public List<RoomSummary> list() { return reg.list(); }
    public void resetAll() { reg.clear(); }
    public boolean closeRoom(String code) { return reg.remove(code); }
    public void leave(String code, String clientId) { reg.leave(code, clientId); }
    public boolean aiAvailable() { return bank.aiAvailable(); }
}
