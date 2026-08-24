// 개장 전 브리핑 프롬프트의 기사 1건 — 전 종목이 대상이라 종목명을 함께 담는다.
package com.finplay.api.domain.feedback.service;

// 브리핑은 시장 단위 단일 질의라 어느 종목 소식인지 모델이 알 수 없다. 그래서 기사마다 종목명을 붙인다
// (spec §LLM 프롬프트의 "[삼성전자] ..." 형식).
public record BriefingNewsItemDto(
	String instrumentName,
	NewsSourceDto source) {
}
