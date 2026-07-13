package com.wordplay.chat;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 방 단위 실시간 채팅(게임 공통). game+roomCode로 채널 분리. */
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chat;

    @PostMapping("/send")
    public ApiResponse<Long> send(@RequestParam String game, @RequestParam String roomCode,
                                  @RequestParam String clientId, @RequestBody Map<String, String> body) {
        validate(game, roomCode, clientId);
        String text = body.get("text");
        if (text == null || text.trim().isEmpty()) throw new BusinessException(ErrorCode.INVALID_INPUT, "메시지가 비었습니다");
        return ApiResponse.success(chat.send(game, roomCode, clientId, body.get("nick"), text));
    }

    @GetMapping("/messages")
    public ApiResponse<List<Map<String, Object>>> messages(@RequestParam String game, @RequestParam String roomCode,
                                                           @RequestParam String clientId,
                                                           @RequestParam(defaultValue = "0") long since) {
        validate(game, roomCode, clientId);
        List<Map<String, Object>> out = chat.since(game, roomCode, since).stream().map(m -> Map.<String, Object>of(
                "seq", m.seq(), "nick", m.nick(), "text", m.text(), "ts", m.ts(),
                "mine", clientId.equals(m.senderId()))).toList();
        return ApiResponse.success(out);
    }

    private void validate(String game, String roomCode, String clientId) {
        if (game == null || game.isBlank() || game.length() > 24
                || roomCode == null || roomCode.isBlank() || roomCode.length() > 24
                || clientId == null || clientId.isBlank() || clientId.length() > 64)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "잘못된 요청입니다");
    }
}
