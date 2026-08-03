// 서술이 무엇으로 만들어졌는지를 나타내는 열거형 — 사후에 LLM/템플릿 비율을 보기 위해 기록한다.
package com.finplay.api.feedback.domain;

/**
 * {@code NONE}은 <b>뉴스 요약·브리핑 전용</b>이다 (ADR-0011, spec §C-4). 여러 기사를 종합하는 요약은
 * 수치 조립으로 대체할 수 없어 템플릿이 없고, 재생성 1회 후에도 후검증에 걸리면 서술 자체가 빈다.
 * 카드·매도 회고는 §템플릿 문장이 있어 {@code LLM} 아니면 {@code TEMPLATE} 둘 중 하나다.
 *
 * <p>{@code narrative_source} 컬럼이 이 열거형을 {@code @Enumerated(STRING)}으로 재사용한다 (§C-8).
 * 값 이름을 바꾸면 저장된 문자열과 어긋나므로 §C-4·§C-8 표기를 그대로 쓴다.
 */
public enum NarrativeSource {

	LLM,
	TEMPLATE,
	NONE
}
