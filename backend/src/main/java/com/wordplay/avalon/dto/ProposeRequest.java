package com.wordplay.avalon.dto;

import java.util.List;

/** 리더의 원정대 제안(좌석 1-based 목록). */
public record ProposeRequest(List<Integer> team) {}
