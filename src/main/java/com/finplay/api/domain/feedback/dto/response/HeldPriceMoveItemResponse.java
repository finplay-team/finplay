// 매도 회고 응답에 실리는 "보유 구간에 걸친 변동 카드" 1건 — 카드와 매수·매도의 시간 간격을 함께 담는다.
package com.finplay.api.domain.feedback.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 계약은 {@code docs/api/feedback.md}의 "매도 직후 피드백 조회" 소절이고 {@code minutesAfterBuy}·
 * {@code minutesBeforeSell}의 계산식은 spec §파생 사실 계산이다.
 *
 * <p><b>{@code PriceMoveListItemResponse}을 재사용하지 않는다.</b> 그 record에는 {@code eventType}이 있고 두 간격 필드가
 * 없다 — 계약이 정한 JSON 필드 집합이 서로 다르므로 한쪽을 다른 쪽으로 대체하면 계약이 깨진다. 근거 목록은
 * 반대로 {@code NewsItem}을 그대로 쓴다(§C-6 — 중첩으로 복제하지 않는다).
 *
 * <p><b>구간은 {@code LocalDateTime}으로 내린다.</b> 저장은 원본 거래일 시간축의 {@code TIME}이고(§C-8) 날짜는
 * 카드의 {@code originTradeDate}를 붙인다 — <b>조회한 날짜가 아니다.</b> 같은 응답의 {@code buyAt}·{@code sellAt}도
 * 같은 축이라 한 객체 안에서 날짜가 갈리지 않는다.
 *
 * <p>목록을 채우는 것은 이 이슈의 2번 항목이다 — {@code sameSessionCompleted=false}이거나 카드가 0건이면
 * {@code []}이고, 카드에도 §C-5의 노출 게이트가 걸린다.
 *
 * @param minutesAfterBuy   카드 {@code windowEnd} − 매수시각 (분)
 * @param minutesBeforeSell 매도시각 − 카드 {@code windowEnd} (분)
 */
public record HeldPriceMoveItemResponse(
	Long id,
	LocalDateTime windowStart,
	LocalDateTime windowEnd,
	BigDecimal changeRate,
	int minutesAfterBuy,
	int minutesBeforeSell,
	String narrative,
	List<NewsItem> sources) {

	// 컬렉션 필드를 가진 record는 방어적 복사가 없으면 spotbugsMain이 EI_EXPOSE_REP으로 잡는다
	// (ai/agent-mistakes.md 2026-07-29).
	public HeldPriceMoveItemResponse {
		sources = List.copyOf(sources);
	}
}
