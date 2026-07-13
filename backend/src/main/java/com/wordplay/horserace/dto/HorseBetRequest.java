package com.wordplay.horserace.dto;

import java.util.List;

/** 배팅 요청. type: WIN(단승)/PLACE(연승)/EXACTA(쌍승)/TRIO(삼복승). picks: 선택한 말 index들. */
public record HorseBetRequest(String type, List<Integer> picks, Long amount) {}
