// 변동 구간 카드가 장중 급변인지 시가 갭인지를 나타내는 열거형 — price_move_events.event_type 컬럼의 저장 문자열이다.
package com.finplay.api.feedback.domain;

/**
 * 이 값이 <b>유니크 키의 일부</b>다 (§데이터 모델). 장중 루프의 첫 후보는 {@code t = 09:05}이고
 * {@code windowStart = t - W = 09:00}인데 시가 갭 카드의 {@code windowStart}도 09:00이라, {@code event_type}이
 * 없으면 둘이 충돌해 나중에 삽입되는 쪽이 조용히 사라진다.
 *
 * <p>{@code @Enumerated(STRING)}으로 저장하므로(§C-8) 값 이름을 바꾸면 저장된 문자열과 어긋난다.
 */
public enum PriceMoveEventType {

	INTRADAY,
	OPENING_GAP
}
