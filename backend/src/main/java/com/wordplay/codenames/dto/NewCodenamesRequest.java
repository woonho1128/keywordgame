package com.wordplay.codenames.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 코드네임 방 생성 요청. */
public record NewCodenamesRequest(@NotBlank @Size(max = 16) String nick) {}
