package com.wordplay.saju.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.wordplay.saju.domain.Gender;
import com.wordplay.saju.domain.SajuType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 사주 조회 요청.
 *
 * @param birthDate 양력 생년월일 (음력 생일은 사용자가 양력으로 변환해서 입력)
 * @param birthTime 출생시각 — timeUnknown 이 true 면 무시된다
 */
public record SajuRequest(

        @NotNull(message = "사주 종류를 선택해주세요")
        SajuType sajuType,

        @Size(max = 20, message = "이름/닉네임은 20자 이하로 입력해주세요")
        String nickname,

        @NotNull(message = "생년월일을 입력해주세요")
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate birthDate,

        @JsonFormat(pattern = "HH:mm")
        LocalTime birthTime,

        boolean timeUnknown,

        @NotNull(message = "성별을 선택해주세요")
        Gender gender
) {

    /** 시각을 모른다고 했거나 값이 비었으면 시주 없이 계산한다 */
    public LocalTime effectiveTime() {
        return timeUnknown ? null : birthTime;
    }
}
