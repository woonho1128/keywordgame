package com.wordplay.saju.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.wordplay.saju.domain.Gender;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

/** 궁합에 들어가는 한 사람의 정보 */
public record PersonInput(

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

    public LocalTime effectiveTime() {
        return timeUnknown ? null : birthTime;
    }

    public String displayName(String fallback) {
        return nickname == null || nickname.isBlank() ? fallback : nickname.trim();
    }
}
