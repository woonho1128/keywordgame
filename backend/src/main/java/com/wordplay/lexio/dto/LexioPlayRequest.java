package com.wordplay.lexio.dto;

import java.util.List;

/** 조합 내기 요청(타일 id 목록). */
public record LexioPlayRequest(List<Integer> tiles) {}
