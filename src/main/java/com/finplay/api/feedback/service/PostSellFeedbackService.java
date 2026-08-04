// 본인 매도 체결 1건의 매도 직후 피드백을 조립하는 조회 서비스 — 원장 수치를 읽기만 하고 원장에 쓰지 않는다.
package com.finplay.api.feedback.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.TradeService;
import com.finplay.api.portfolio.service.SellAllocationQueryService;
import com.finplay.api.portfolio.service.SellAllocationSummaryDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계약은 {@code docs/api-contracts.md}의 "매도 직후 피드백 조회" 소절이고 요구사항은 spec FEED-007이다.
 *
 * <p><b>검증 순서를 바꾸지 않는다.</b> {@code TradeService.getOwnedTrade}가 이미 정한 존재(404 {@code NOT_FOUND})
 * → 소유(403 {@code FORBIDDEN})를 그대로 타고, 그 뒤에 매수 체결 → 400, 코인 체결 → 400이다. 매도 회고
 * 투자일기({@code JournalService})가 같은 순서를 쓰고 있다.
 *
 * <p><b>코인은 빈 값을 채운 200을 돌려주지 않는다</b>(FEED-007 각주) — 게이트가 전부 장 마감과 원본 거래일에
 * 묶여 있는데 24시간 거래인 코인에는 둘 다 없다. 코인 매도 회고는 3차다.
 *
 * <p><b>수치는 원장에서 그대로 읽는다.</b> {@code buyPrice}는 FIFO 배분 가중평균 매수단가,
 * {@code sellPrice}·{@code quantity}·{@code fee}·{@code realizedPnl}은 {@code trades} 행 그대로다. 재계산하거나
 * LLM에게 계산시키지 않는다(PRD C-004). 배분·lot은 {@code portfolio} 소유라 서비스를 경유한다(§C-6).
 *
 * <p><b>원장에 쓰지 않는다.</b> 이 spec이 회원별로 쓰는 테이블은 {@code trade_feedbacks} 하나뿐이고, 그 쓰기는
 * 서술을 저장하는 항목(이슈 #208의 4·5번)이 별도 {@code @Transactional} 컴포넌트로 더한다 — <b>LLM 호출을
 * 트랜잭션 안에 넣지 않기 위해서다.</b> 이 클래스는 그때까지 읽기 전용이다.
 */
@Service
@RequiredArgsConstructor
public class PostSellFeedbackService {

	// 계약이 정한 수익률 scale·라운딩. PortfolioService·HoldingValuationService와 같은 값이다.
	private static final int RETURN_RATE_SCALE = 4;

	private final TradeService tradeService;

	private final SellAllocationQueryService sellAllocationQueryService;

	/**
	 * 본인 매도 체결 1건의 회고를 조회한다.
	 *
	 * @param tradeId 미존재는 404 {@code NOT_FOUND}, 타인 체결은 403 {@code FORBIDDEN}, 매수·코인 체결은 400
	 *     {@code VALIDATION_ERROR}다
	 * @return 원장 수치와 {@code sameSessionCompleted}까지 채운 응답. 파생 사실·카드·매도 후 흐름·반사실·
	 *     집단 비교·서술은 뒤 항목이 채운다
	 */
	@Transactional(readOnly = true)
	public PostSellFeedbackResponse getPostSellFeedback(Long userId, Long tradeId) {
		Trade trade = tradeService.getOwnedTrade(userId, tradeId);
		if (trade.getSide() != OrderSide.SELL) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		if (trade.getInstrument().getMarket() == Market.CRYPTO) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}

		SellAllocationSummaryDto allocation = sellAllocationQueryService.getSellAllocationSummary(tradeId);
		LocalDate sellSourceTradingDate = sourceTradingDateOf(trade);
		LocalDateTime buyAt = atOriginTradeDate(
			allocation.earliestBuyAt(), allocation.earliestBuySourceTradingDate());
		LocalDateTime sellAt = atOriginTradeDate(trade.getExecutedAt(), sellSourceTradingDate);

		return new PostSellFeedbackResponse(
			trade.getId(),
			trade.getInstrument().getId(),
			trade.getInstrument().getSymbol(),
			trade.getInstrument().getName(),
			buyAt,
			sellAt,
			allocation.buyPrice(),
			trade.getPrice(),
			trade.getQuantity(),
			trade.getFee(),
			trade.getRealizedPnl(),
			returnRate(trade, allocation),
			(int)Duration.between(buyAt, sellAt).toMinutes(),
			isSameSessionCompleted(sellSourceTradingDate, allocation),
			// 보유 구간 극값·뉴스 대비 타이밍·보유 구간 카드 — 2번 항목이 자기 완료 조건과 함께 채운다.
			// 계약의 필드 집합을 지금부터 유지한다: 필드를 나중에 더하면 그 사이 계약이 깨진 상태로 머지된다.
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			List.of(),
			// 매도 후 흐름·반사실·집단 비교 — 3번 항목이 §C-5 게이트 판정과 함께 채운다.
			null,
			null,
			null,
			// AI 서술 — 4·5번 항목이 생성·저장·재사용·재생성과 함께 채운다. narrativeStatus는 항상 READY이고
			// 이 엔드포인트에 UNAVAILABLE이 존재하지 않는다(§C-4).
			null,
			null,
			null);
	}

	/**
	 * {@code realizedPnl ÷ (배분된 매수원가 합 + 배분된 매수수수료 합)}. 계약이 정한 식·scale·라운딩 그대로다.
	 *
	 * <p>분모가 0이면 {@code ZERO}로 둔다 — 배분 원가와 수수료가 동시에 0인 체결은 원장에 생기지 않지만
	 * {@code ArithmeticException}으로 조회 전체가 500이 되는 것보다 낫다.
	 */
	private BigDecimal returnRate(Trade trade, SellAllocationSummaryDto allocation) {
		long buyBasis = allocation.allocatedCost() + allocation.allocatedBuyFee();
		if (buyBasis == 0L || trade.getRealizedPnl() == null) {
			return BigDecimal.ZERO;
		}
		return BigDecimal.valueOf(trade.getRealizedPnl())
			.divide(BigDecimal.valueOf(buyBasis), RETURN_RATE_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * <b>배분된 lot 전부를 본다</b>(FEED-007). 각 lot의 매수 체결과 이 매도 체결의
	 * {@code stockReplaySession.sourceTradingDate}를 대조하고 <b>하나라도 다르면 {@code false}</b>다 — 가장 이른
	 * lot 하나만 보고 판정하면 그 뒤 lot이 다른 재생일이어도 {@code true}가 되고, 그러면 분봉이 불연속인 구간에서
	 * 극값·반사실을 계산해 예외도 로그도 없이 틀린 값이 나간다.
	 *
	 * <p>매도 체결의 원본 거래일을 모르면 {@code false}다 — 대조 기준이 없는데 {@code true}로 두면 위와 같은
	 * 상태가 된다.
	 */
	private boolean isSameSessionCompleted(
		LocalDate sellSourceTradingDate, SellAllocationSummaryDto allocation) {
		if (sellSourceTradingDate == null) {
			return false;
		}
		return allocation.buySourceTradingDates().stream().allMatch(sellSourceTradingDate::equals);
	}

	private LocalDate sourceTradingDateOf(Trade trade) {
		StockReplaySession session = trade.getStockReplaySession();
		return session == null ? null : session.getSourceTradingDate();
	}

	/**
	 * 체결 시각을 <b>원본 거래일 시간축</b>으로 옮긴다. 재생이 1배속이라 원본 거래일의 시각과 서비스 날짜의
	 * 벽시계 시각이 1:1로 대응하므로 {@code LocalTime}은 그대로고 날짜만 갈린다(spec §핵심 제약 — 재생 시간축).
	 *
	 * <p>계약이 {@code buyAt}·{@code sellAt}을 <b>"원본 거래일 기준 체결 시각"</b>으로 정했다. 같은 응답의
	 * {@code priceMoves[].windowStart}·{@code postSellFlow.closeAt}·{@code holdHighAt}이 전부 원본 거래일 축이라,
	 * 여기만 서비스 날짜로 두면 한 객체 안에서 날짜가 갈려 화면이 시간축을 복원할 수 없다.
	 *
	 * <p>원본 거래일을 모르면 체결 시각을 그대로 둔다 — 주식 체결에는 항상 재생세션이 있고({@code Trade}가 그것을
	 * 강제한다) 거래가 성립한 세션은 {@code READY}라 실제로는 도달하지 않는 자리다.
	 */
	private static LocalDateTime atOriginTradeDate(LocalDateTime executedAt, LocalDate originTradeDate) {
		return originTradeDate == null ? executedAt : LocalDateTime.of(originTradeDate, executedAt.toLocalTime());
	}
}
