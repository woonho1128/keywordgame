package com.wordplay.rummikub.dto;

import java.util.List;

/** 턴 제출: 제안하는 테이블 전체(세트별 타일 id 목록). */
public record PlayRequest(List<List<Integer>> table) {}
