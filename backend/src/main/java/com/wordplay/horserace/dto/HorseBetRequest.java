package com.wordplay.horserace.dto;

/** 배팅 요청. type: WIN(단승) / PLACE(연승). */
public record HorseBetRequest(String type, Integer horseIndex, Long amount) {}
