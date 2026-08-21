// 매도 회고의 원장 컨텍스트(존재·소유·매도 체결 검증 + 배분 요약)를 한 읽기 트랜잭션에서 읽는 컴포넌트.
package com.finplay.api.domain.feedback.service;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.portfolio.service.SellAllocationQueryService;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * spec §FEED-012 결정 5의 <b>트랜잭션 A</b>다 — 매도 회고가 조립 전에 원장에서 읽어야 하는 것 전부를 한 트랜잭션
 * 안에서 읽고 닫는다. 조립과 외부 호출(코인 REST·LLM)은 이 트랜잭션 밖에서 일어난다.
 *
 * <p><b>왜 {@link PostSellFeedbackReader}에서 떼어 냈는가.</b> 오케스트레이터 메서드에 {@code @Transactional}이
 * 남아 있으면, 그 안에서 부르는 <b>다른 빈</b>의 메서드는 자기호출이 아니어도 전파 기본값 {@code REQUIRED}에 따라
 * 이미 열린 트랜잭션에 그대로 합류한다 — 이슈 #282의 버그가 정확히 그 경로였다(코인 리더에 {@code @Transactional}이
 * 없는데도 빗썸 REST가 트랜잭션 안에 갇혔다). 그래서 분리의 핵심은 애노테이션을 어디에 붙이는가가 아니라
 * <b>오케스트레이터에 애노테이션이 전혀 없어야 한다</b>이고, 그러려면 트랜잭션을 가진 이 빈이 따로 있어야 한다.
 *
 * <p><b>검증 순서를 바꾸지 않는다.</b> {@code TradeService.getOwnedTrade}가 이미 정한 존재(404 {@code NOT_FOUND})
 * → 소유(403 {@code FORBIDDEN})를 그대로 타고, 그 뒤에 매수 체결 → 400이다. <b>매도 체결 검증이 배분 조회보다
 * 먼저다</b> — 뒤로 미루면 400이 될 체결로도 배분 집계를 한 번 돌린다.
 */
@Component
@RequiredArgsConstructor
class PostSellFeedbackContextReader {

	private final TradeService tradeService;

	private final SellAllocationQueryService sellAllocationQueryService;

	/**
	 * 본인 매도 체결 1건의 원장 컨텍스트를 읽는다.
	 *
	 * <p><b>반환 직전의 두 {@code Hibernate.initialize}가 이 설계의 숨은 필수 조건이다.</b>
	 * {@code Trade.instrument}·{@code Trade.stockReplaySession}은 {@code FetchType.LAZY}이고
	 * {@code spring.jpa.open-in-view=false}라, 이 메서드가 끝나면 세션이 닫혀 프록시를 더는 열 수 없다. 그런데
	 * 호출부는 트랜잭션 밖에서 {@code trade.getInstrument().getMarket()}(시장 분기)과
	 * {@code trade.getStockReplaySession()}(원본 거래일·서비스 날짜)을 읽는다 — 미리 채워 두지 않으면
	 * {@code LazyInitializationException}이다. <b>컴파일도 되고 목 기반 단위 테스트도 통과한다</b>(목은 실제 Hibernate
	 * 세션을 거치지 않는다). 실제 DB로 돌리는 순간에만 500이 나므로 지우지 마라 — {@code stockReplaySession}은 코인
	 * 체결에서 {@code null}이고 {@code Hibernate.initialize(null)}은 안전한 no-op이다.
	 *
	 * @param tradeId 미존재는 404 {@code NOT_FOUND}, 타인 체결은 403 {@code FORBIDDEN}, 매수 체결은 400
	 *     {@code VALIDATION_ERROR}다
	 */
	@Transactional(readOnly = true)
	PostSellFeedbackContext loadContext(Long userId, Long tradeId) {
		Trade trade = tradeService.getOwnedTrade(userId, tradeId);
		if (trade.getSide() != OrderSide.SELL) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}

		SellAllocationSummaryDto allocation = sellAllocationQueryService.getSellAllocationSummary(tradeId);

		Hibernate.initialize(trade.getInstrument());
		Hibernate.initialize(trade.getStockReplaySession());
		return new PostSellFeedbackContext(trade, allocation);
	}

}
