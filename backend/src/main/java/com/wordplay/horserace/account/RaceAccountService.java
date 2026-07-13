package com.wordplay.horserace.account;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 경마 계정(영속 지갑) 서비스. 로그인/가입·토큰·재기 보너스·리더보드·
 * 계정 동시 1개 방 제한·정산 반영을 담당한다.
 */
@Service
@RequiredArgsConstructor
public class RaceAccountService {

    public static final long START_BALANCE = 10_000;
    public static final long BONUS_AMOUNT = 2_000;
    public static final long BONUS_THRESHOLD = 100;   // 이 미만이면 파산으로 보고 재기 보너스 허용
    public static final int  BONUS_MAX_PER_DAY = 3;
    private static final int LOCK_FAILS = 5;
    private static final long LOCK_MS = 60_000;

    private final RaceAccountRepository repo;

    private final Map<String, Long> tokenToAccount = new ConcurrentHashMap<>();
    private final Map<Long, String> accountToRoom = new ConcurrentHashMap<>();
    private final Map<String, int[]> failState = new ConcurrentHashMap<>(); // nick -> [fails, untilMsHi, untilMsLo] 간이

    public record AuthResult(String token, long accountId, String nickname, long balance,
                             long peakBalance, int totalRaces, int wins, boolean created) {}

    /** 로그인 또는 가입(닉네임이 없으면 신규 생성). */
    @Transactional
    public AuthResult authenticate(String nickname, String password) {
        String nick = validateNick(nickname);
        if (password == null || password.length() < 4 || password.length() > 64)
            throw bad("암호는 4~64자여야 합니다");
        checkLock(nick);

        var existing = repo.findByNickname(nick);
        if (existing.isEmpty()) {
            RaceAccount acc = RaceAccount.builder()
                    .nickname(nick)
                    .passwordHash(PasswordHasher.hash(password))
                    .balance(START_BALANCE).peakBalance(START_BALANCE)
                    .totalRaces(0).wins(0)
                    .lastLoginAt(Instant.now()).createdAt(Instant.now())
                    .bonusCountToday(0)
                    .build();
            acc = repo.save(acc);
            return toAuth(acc, issueToken(acc.getId()), true);
        }
        RaceAccount acc = existing.get();
        if (!PasswordHasher.verify(password, acc.getPasswordHash())) {
            registerFail(nick);
            throw bad("암호가 올바르지 않습니다");
        }
        clearFail(nick);
        acc.setLastLoginAt(Instant.now());
        repo.save(acc);
        return toAuth(acc, issueToken(acc.getId()), false);
    }

    public RaceAccount requireByToken(String token) {
        Long id = token == null ? null : tokenToAccount.get(token);
        if (id == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "로그인이 필요합니다(토큰 만료)");
        return repo.findById(id).orElseThrow(() -> bad("계정을 찾을 수 없습니다"));
    }

    public Long accountIdOf(String token) {
        return token == null ? null : tokenToAccount.get(token);
    }

    /** 재기 보너스: 파산 상태 + 일일 횟수 이내일 때만 지급. */
    @Transactional
    public long claimBonus(String token) {
        RaceAccount acc = requireByToken(token);
        if (acc.getBalance() >= BONUS_THRESHOLD) throw bad("아직 파산이 아니라 재기 보너스를 받을 수 없어요");
        LocalDate today = LocalDate.now();
        if (!today.equals(acc.getLastBonusDate())) {
            acc.setLastBonusDate(today);
            acc.setBonusCountToday(0);
        }
        if (acc.getBonusCountToday() >= BONUS_MAX_PER_DAY)
            throw bad("오늘 재기 보너스를 모두 사용했어요(하루 " + BONUS_MAX_PER_DAY + "회)");
        acc.setBonusCountToday(acc.getBonusCountToday() + 1);
        acc.setBalance(acc.getBalance() + BONUS_AMOUNT);
        repo.save(acc);
        return acc.getBalance();
    }

    public List<RaceAccount> leaderboard() {
        return repo.findTop20ByOrderByBalanceDesc();
    }

    // ---------- 관리자 ----------

    public List<RaceAccount> adminAll() {
        return repo.findAllByOrderByBalanceDesc();
    }

    /** 관리자: 잔고 증감(음수면 차감). 반환값은 변경 후 잔고. */
    @Transactional
    public long adminGrant(String nickname, long amount) {
        RaceAccount a = repo.findByNickname(nickname == null ? "" : nickname.trim())
                .orElseThrow(() -> bad("계정을 찾을 수 없습니다: " + nickname));
        a.setBalance(Math.max(0, a.getBalance() + amount));
        if (a.getBalance() > a.getPeakBalance()) a.setPeakBalance(a.getBalance());
        repo.save(a);
        return a.getBalance();
    }

    /** 관리자: 계정 삭제(활성 토큰·방 점유도 정리). */
    @Transactional
    public void adminDelete(String nickname) {
        RaceAccount a = repo.findByNickname(nickname == null ? "" : nickname.trim())
                .orElseThrow(() -> bad("계정을 찾을 수 없습니다: " + nickname));
        tokenToAccount.values().removeIf(id -> id.equals(a.getId()));
        accountToRoom.remove(a.getId());
        repo.delete(a);
    }

    // ---------- 동시 1개 방 제한 ----------

    public void enterRoom(long accountId, String roomCode) {
        String cur = accountToRoom.get(accountId);
        if (cur != null && !cur.equals(roomCode))
            throw bad("이미 다른 방에서 플레이 중이에요");
        accountToRoom.put(accountId, roomCode);
    }

    public void leaveRoom(long accountId, String roomCode) {
        accountToRoom.remove(accountId, roomCode);
    }

    // ---------- 정산 반영 ----------

    /** 레이스 정산 결과를 계정 잔고에 반영. */
    @Transactional
    public void applySettlement(long accountId, long newBalance, boolean raced, boolean won) {
        repo.findById(accountId).ifPresent(acc -> {
            acc.setBalance(Math.max(0, newBalance));
            if (acc.getBalance() > acc.getPeakBalance()) acc.setPeakBalance(acc.getBalance());
            if (raced) acc.setTotalRaces(acc.getTotalRaces() + 1);
            if (won) acc.setWins(acc.getWins() + 1);
            repo.save(acc);
        });
    }

    // ---------- 내부 ----------

    private String issueToken(long accountId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        tokenToAccount.put(token, accountId);
        return token;
    }

    private AuthResult toAuth(RaceAccount a, String token, boolean created) {
        return new AuthResult(token, a.getId(), a.getNickname(), a.getBalance(),
                a.getPeakBalance(), a.getTotalRaces(), a.getWins(), created);
    }

    private void checkLock(String nick) {
        int[] st = failState.get(nick);
        if (st != null && st[0] >= LOCK_FAILS) {
            long until = ((long) st[1] << 32) | (st[2] & 0xFFFFFFFFL);
            if (System.currentTimeMillis() < until)
                throw bad("로그인 시도가 많아 잠시 잠겼어요. 잠시 후 다시 시도하세요");
        }
    }
    private void registerFail(String nick) {
        long until = System.currentTimeMillis() + LOCK_MS;
        int[] st = failState.computeIfAbsent(nick, k -> new int[3]);
        st[0]++; st[1] = (int) (until >> 32); st[2] = (int) until;
    }
    private void clearFail(String nick) { failState.remove(nick); }

    private String validateNick(String nickname) {
        String t = nickname == null ? "" : nickname.trim();
        if (t.isEmpty()) throw bad("닉네임을 입력하세요");
        if (t.length() > 16) throw bad("닉네임은 16자 이하여야 합니다");
        return t;
    }

    private static BusinessException bad(String msg) {
        return new BusinessException(ErrorCode.INVALID_INPUT, msg);
    }
}
