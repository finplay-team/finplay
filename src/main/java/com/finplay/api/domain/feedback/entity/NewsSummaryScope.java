// 뉴스 요약이 다루는 시간 범위 — 프롬프트의 "범위:" 줄이 이 값에 따라 갈린다.
package com.finplay.api.domain.feedback.entity;

/**
 * 세 값이 같은 조립 경로를 타므로 범위를 프롬프트에 넣지 않으면 {@code PRE_MARKET} 생성에 {@code FULL}
 * 기사가 섞여도 모델이 알 수 없다 (spec §LLM 프롬프트). 각 값이 실제로 가리키는 구간은 §C-2가 정본이며,
 * 아래 문자열은 모델에게 주는 자연어라 시각 리터럴을 그대로 적는다 — §C-2 참조 규칙의 의도된 예외다.
 * §C-2를 바꾸면 이 문자열도 함께 고친다.
 *
 * <p>{@code instrument_news_summaries.scope} 컬럼이 이 열거형을 {@code @Enumerated(STRING)}으로
 * 재사용한다 (§C-8) — {@code NarrativeSource}와 같은 방식이다.
 */
public enum NewsSummaryScope {

	PRE_MARKET("직전 거래일 장 마감(15:30) 이후 ~ 당일 개장(09:00) 전"),
	FULL("직전 거래일 15:30 ~ 원본 거래일 15:30"),
	ROLLING_24H("최근 24시간");

	private final String promptText;

	NewsSummaryScope(String promptText) {
		this.promptText = promptText;
	}

	public String promptText() {
		return this.promptText;
	}
}
