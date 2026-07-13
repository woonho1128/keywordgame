package com.wordplay.horserace;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.dto.CreateRoomResponse;
import com.wordplay.common.dto.RoomSummary;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.horserace.account.RaceAccount;
import com.wordplay.horserace.account.RaceAccountService;
import com.wordplay.horserace.dto.HorseBetRequest;
import com.wordplay.horserace.dto.HorseRaceStateResponse;
import com.wordplay.horserace.dto.NewHorseRaceRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/horserace")
@RequiredArgsConstructor
public class HorseRaceController {

    private final HorseRaceRoomManager rooms;
    private final RaceAccountService accounts;

    @Value("${app.spyfall.admin-code}")
    private String adminCode;

    // ============ 계정 ============

    @PostMapping("/account/auth")
    public ApiResponse<RaceAccountService.AuthResult> auth(@RequestBody Map<String, String> body) {
        return ApiResponse.success(accounts.authenticate(body.get("nickname"), body.get("password")));
    }

    @GetMapping("/account/me")
    public ApiResponse<RaceAccountService.AuthResult> accountMe(@RequestParam String token) {
        RaceAccount a = accounts.requireByToken(token);
        return ApiResponse.success(new RaceAccountService.AuthResult(
                token, a.getId(), a.getNickname(), a.getBalance(), a.getPeakBalance(),
                a.getTotalRaces(), a.getWins(), false));
    }

    @PostMapping("/account/bonus")
    public ApiResponse<Long> bonus(@RequestParam String token, @RequestParam(required = false) String roomCode) {
        long newBalance = accounts.claimBonus(token);
        Long accId = accounts.accountIdOf(token);
        if (roomCode != null && accId != null) {
            HorseRaceGame g = rooms.find(roomCode);
            if (g != null) g.syncAccountChips(accId, newBalance);
        }
        return ApiResponse.success(newBalance);
    }

    @GetMapping("/admin/accounts")
    public ApiResponse<List<Map<String, Object>>> adminAccounts(@RequestParam String code) {
        requireAdmin(code);
        List<Map<String, Object>> out = accounts.adminAll().stream().map(a -> Map.of(
                "nickname", (Object) a.getNickname(), "balance", a.getBalance(),
                "peak", a.getPeakBalance(), "races", a.getTotalRaces(), "wins", a.getWins())).toList();
        return ApiResponse.success(out);
    }

    @PostMapping("/admin/grant")
    public ApiResponse<Long> adminGrant(@RequestParam String code, @RequestParam String nickname, @RequestParam long amount) {
        requireAdmin(code);
        return ApiResponse.success(accounts.adminGrant(nickname, amount));
    }

    @PostMapping("/admin/delete")
    public ApiResponse<Boolean> adminDelete(@RequestParam String code, @RequestParam String nickname) {
        requireAdmin(code);
        accounts.adminDelete(nickname);
        return ApiResponse.success(true);
    }

    private void requireAdmin(String code) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
    }

    @GetMapping("/leaderboard")
    public ApiResponse<List<Map<String, Object>>> leaderboard() {
        List<Map<String, Object>> out = accounts.leaderboard().stream().map(a -> Map.of(
                "nickname", (Object) a.getNickname(), "balance", a.getBalance(),
                "peak", a.getPeakBalance(), "races", a.getTotalRaces(), "wins", a.getWins())).toList();
        return ApiResponse.success(out);
    }

    // ============ 방/게임 ============

    @GetMapping("/rooms")
    public ApiResponse<List<RoomSummary>> roomList() {
        return ApiResponse.success(rooms.list());
    }

    @PostMapping("/new")
    public ApiResponse<CreateRoomResponse<HorseRaceStateResponse>> newGame(@RequestParam String clientId,
                                                                           @Valid @RequestBody NewHorseRaceRequest req) {
        validateClientId(clientId);
        Long accId = accounts.accountIdOf(req.token());
        long bal = accId != null ? accounts.requireByToken(req.token()).getBalance() : 0;
        String code = rooms.create(clientId, req, accId, bal);
        if (accId != null) accounts.enterRoom(accId, code);
        HorseRaceGame g = rooms.require(code);
        return ApiResponse.success(new CreateRoomResponse<>(code, g.me(clientId)));
    }

    @PostMapping("/join")
    public ApiResponse<HorseRaceStateResponse> join(@RequestParam String roomCode, @RequestParam String clientId,
                                                    @RequestBody JoinReqWithToken req) {
        validateClientId(clientId);
        Long accId = accounts.accountIdOf(req.token());
        long bal = accId != null ? accounts.requireByToken(req.token()).getBalance() : 0;
        if (accId != null) accounts.enterRoom(accId, roomCode);
        HorseRaceGame g = rooms.require(roomCode);
        HorseRaceStateResponse st = g.join(clientId, req.nick(), accId, bal);
        return ApiResponse.success(flush(g, st));
    }

    public record JoinReqWithToken(String nick, String token) {}

    @PostMapping("/add-bot")
    public ApiResponse<HorseRaceStateResponse> addBot(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        return ApiResponse.success(rooms.require(roomCode).addBot(clientId));
    }

    @PostMapping("/start")
    public ApiResponse<HorseRaceStateResponse> start(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        HorseRaceGame g = rooms.require(roomCode);
        return ApiResponse.success(flush(g, g.start(clientId)));
    }

    @PostMapping("/bet")
    public ApiResponse<HorseRaceStateResponse> bet(@RequestParam String roomCode, @RequestParam String clientId,
                                                   @RequestBody HorseBetRequest req) {
        validateClientId(clientId);
        HorseRaceGame g = rooms.require(roomCode);
        long amt = req.amount() == null ? 0 : req.amount();
        int[] picks = req.picks() == null ? new int[0] : req.picks().stream().mapToInt(Integer::intValue).toArray();
        return ApiResponse.success(flush(g, g.bet(clientId, req.type(), picks, amt)));
    }

    @PostMapping("/next-race")
    public ApiResponse<HorseRaceStateResponse> nextRace(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        HorseRaceGame g = rooms.require(roomCode);
        return ApiResponse.success(flush(g, g.nextRace(clientId)));
    }

    @PostMapping("/end")
    public ApiResponse<HorseRaceStateResponse> end(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        HorseRaceGame g = rooms.require(roomCode);
        return ApiResponse.success(flush(g, g.endGame(clientId)));
    }

    @GetMapping("/me")
    public ApiResponse<HorseRaceStateResponse> me(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        HorseRaceGame g = rooms.find(roomCode);
        if (g == null) return ApiResponse.success(HorseRaceStateResponse.notStarted(System.currentTimeMillis()));
        return ApiResponse.success(flush(g, g.me(clientId)));
    }

    @PostMapping("/reset")
    public ApiResponse<HorseRaceStateResponse> reset(@RequestParam String code) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        rooms.resetAll();
        return ApiResponse.success(HorseRaceStateResponse.notStarted(System.currentTimeMillis()));
    }

    @PostMapping("/close-room")
    public ApiResponse<Boolean> closeRoom(@RequestParam String code, @RequestParam String roomCode) {
        if (!adminCode.equals(code)) throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 코드가 올바르지 않습니다");
        return ApiResponse.success(rooms.closeRoom(roomCode));
    }

    @PostMapping("/leave")
    public ApiResponse<Boolean> leave(@RequestParam String roomCode, @RequestParam String clientId) {
        validateClientId(clientId);
        HorseRaceGame g = rooms.find(roomCode);
        if (g != null) {
            Long accId = g.accountIdOfClient(clientId);
            if (accId != null) accounts.leaveRoom(accId, roomCode);
        }
        rooms.leave(roomCode, clientId);
        return ApiResponse.success(true);
    }

    /** 게임 상태 반환 전 계정 정산을 DB에 flush. */
    private HorseRaceStateResponse flush(HorseRaceGame g, HorseRaceStateResponse st) {
        for (HorseRaceGame.AccountSettle s : g.pollAccountSettlements())
            accounts.applySettlement(s.accountId(), s.balance(), s.raced(), s.won());
        return st;
    }

    private void validateClientId(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 64)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "clientId가 올바르지 않습니다");
    }
}
