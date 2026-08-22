// 수집한 항목이 뉴스인지 공시인지를 나타내는 열거형 — market_news_items.type 컬럼의 저장 문자열이다.
package com.finplay.api.domain.feedback.entity;

/**
 * 뉴스와 공시를 한 테이블에 담되 <b>판정 규칙이 다르다</b>. 뉴스는 분 단위 발행시각이 있어 구간 필터를 그대로
 * 쓰지만, 공시는 OpenDART가 접수일자만 주어 {@code published_at}의 시각 부분이 항상 {@code 00:00:00}이라
 * 날짜로 판정한다 (spec §C-3). 두 종류를 이 값으로 갈라야 그 규칙이 성립한다.
 *
 * <p>{@code type} 컬럼이 이 열거형을 {@code @Enumerated(STRING)}으로 저장한다 (§C-8). 값 이름을 바꾸면
 * 저장된 문자열과 어긋나므로 §데이터 모델 표기를 그대로 쓴다.
 */
public enum MarketNewsItemType {

	NEWS,
	DISCLOSURE
}
