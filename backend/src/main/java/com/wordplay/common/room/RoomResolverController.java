package com.wordplay.common.room;

import com.wordplay.common.dto.ApiResponse;
import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 코드로 어느 게임 방인지 해석한다(메인에서 코드만 입력해 바로 입장).
 * 코드는 전역에서 유일하게 발급되므로 게임 키가 유일하게 결정된다.
 */
@RestController
@RequestMapping("/api/v1/rooms")
public class RoomResolverController {

    @GetMapping("/resolve")
    public ApiResponse<Map<String, String>> resolve(@RequestParam String code) {
        String game = RoomRegistry.resolveGame(code);
        if (game == null) throw new BusinessException(ErrorCode.INVALID_INPUT, "해당 코드의 방을 찾을 수 없습니다");
        return ApiResponse.success(Map.of("game", game, "code", code.toUpperCase()));
    }
}
