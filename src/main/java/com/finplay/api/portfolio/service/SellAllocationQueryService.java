// 매도 체결 1건의 FIFO 배분·lot을 읽어 회고용 수치로 요약하는 읽기 전용 서비스
package com.finplay.api.portfolio.service;

import com.finplay.api.order.domain.Trade;
import com.finplay.api.portfolio.domain.HoldingLot;
import com.finplay.api.portfolio.domain.TradeAllocation;
import com.finplay.api.portfolio.repository.TradeAllocationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * spec 012 §C-6이 {@code portfolio}에 요구한 "배분·lot 조회"다 — {@code feedback}의 매도 회고(FEED-007)가 쓴다.
 * 집단 비교의 모집단 재구성은 {@code plan.md} 7번이 이 패키지에 따로 더한다.
 *
 * <p><b>쓰지 않는다.</b> 원장(주문·체결·계좌·보유·lot·배분)은 이 spec의 어떤 코드도 바꾸지 않는다 — 매도 회고가
 * 쓰는 테이블은 {@code trade_feedbacks} 하나뿐이다.
 */
@Service
@RequiredArgsConstructor
public class SellAllocationQueryService {

	// Holding.averagePrice와 같은 정밀도. 단가 컬럼(holding_lots.unit_cost·trades.price)의 scale이 8이다.
	private static final int BUY_PRICE_SCALE = 8;

	private final TradeAllocationRepository tradeAllocationRepository;

	/**
	 * 매도 체결 1건의 배분을 요약한다.
	 *
	 * @param sellTradeId 매도 체결 id. 소유권 검증은 호출부(체결 조회)가 이미 끝냈다고 본다
	 * @return 가중평균 매수단가·가장 이른 매수 시각·배분 합계와 lot별 원본 거래일
	 * @throws IllegalStateException 배분이 0건이면 원장 불일치다 — 매도 체결은 항상 lot을 소비하며
	 *     ({@code PortfolioSellService.applySellTrade}) 잔여수량이 남으면 그 자리에서 이미 거부된다. 여기서
	 *     빈 요약을 돌려주면 매수단가가 0으로 나누어지거나 조용히 {@code null}이 된다
	 */
	@Transactional(readOnly = true)
	public SellAllocationSummaryDto getSellAllocationSummary(Long sellTradeId) {
		List<TradeAllocation> allocations = tradeAllocationRepository
			.findAllBySellTradeIdOrderByLotExecutedAtAscLotIdAsc(sellTradeId);
		if (allocations.isEmpty()) {
			throw new IllegalStateException("매도 체결에 배분된 lot이 없습니다. sellTradeId=" + sellTradeId);
		}

		long allocatedCost = 0L;
		long allocatedBuyFee = 0L;
		BigDecimal allocatedQuantity = BigDecimal.ZERO;
		List<LocalDate> buySourceTradingDates = new ArrayList<>();

		for (TradeAllocation allocation : allocations) {
			allocatedCost += allocation.getAllocatedCost();
			allocatedBuyFee += allocation.getAllocatedBuyFee();
			allocatedQuantity = allocatedQuantity.add(allocation.getAllocatedQuantity());
			buySourceTradingDates.add(sourceTradingDateOf(allocation.getHoldingLot()));
		}

		// 정렬이 lot 체결시각 오름차순이라 첫 배분의 lot이 가장 이른 매수다 (FEED-007 — 가장 이른 executed_at).
		HoldingLot earliestLot = allocations.get(0).getHoldingLot();
		BigDecimal buyPrice = BigDecimal.valueOf(allocatedCost)
			.divide(allocatedQuantity, BUY_PRICE_SCALE, RoundingMode.HALF_UP);

		return new SellAllocationSummaryDto(
			buyPrice,
			earliestLot.getExecutedAt(),
			sourceTradingDateOf(earliestLot),
			allocatedCost,
			allocatedBuyFee,
			allocatedQuantity,
			buySourceTradingDates);
	}

	/**
	 * 매도 체결 1건에 배분된 <b>매수 체결 id</b>를 매수 시각 오름차순으로 돌려준다 (spec 012 §C-6 · §FEED-013
	 * 결정 4). {@code feedback}의 투자일기 반영(4차)이 이 id들로 매수 회고를 읽는다.
	 *
	 * <p><b>순서를 유지한 채 중복을 제거한다.</b> 중복이 생기는 자리는 {@code holding_lots}가 아니라
	 * {@code trade_allocations}다 — lot ↔ 매수 체결은 {@code uk_holding_lots_buy_trade}(V10)가 1:1로 강제하지만,
	 * 배분 테이블에는 {@code (sell_trade_id, holding_lot_id)} 유니크가 없어 <b>같은 lot이 한 매도에 두 번 배분될 수
	 * 있다</b>(2026-08-16 tester 실측 — 반대 방향 픽스처는 유니크 위반으로 만들어지지도 않는다). 같은 일기를 두 번
	 * 실을 이유가 없으므로 제거는 그대로 필요하다. 정렬은
	 * {@code findAllBySellTradeIdOrderByLotExecutedAtAscLotIdAsc}가 이미 lot 체결시각 오름차순으로 준 것을 그대로
	 * 따른다 — 서술의 시간 축(FEED-007의 "가장 이른 {@code executed_at}")과 일기 순서가 같아진다.
	 *
	 * <p><b>{@link SellAllocationSummaryDto}에 필드를 더하지 않고 조회 경로를 따로 둔다</b>(§C-6). 기존 소비자의
	 * DTO 모양을 바꾸지 않는 쪽이 안전하다.
	 *
	 * <p><b>배분이 0건이어도 예외를 던지지 않는다.</b> {@link #getSellAllocationSummary}는 원장 불일치를 드러내려고
	 * {@link IllegalStateException}을 던지지만, 이 메서드는 그 요약이 이미 성공한 뒤에만 불리므로 0건이 나올 수
	 * 없다. 만약 나온다면 <b>서술 재료가 없는 것일 뿐 조회를 죽일 이유가 아니다</b> — 일기가 하나도 없는 상태와
	 * 같은 뜻이 된다.
	 *
	 * @param sellTradeId 매도 체결 id. 소유권 검증은 호출부(체결 조회)가 이미 끝냈다고 본다
	 * @return 매수 시각 오름차순 매수 체결 id (중복 없음). 배분이 없으면 빈 목록
	 */
	@Transactional(readOnly = true)
	public List<Long> getAllocatedBuyTradeIds(Long sellTradeId) {
		Set<Long> buyTradeIds = new LinkedHashSet<>();
		for (TradeAllocation allocation : tradeAllocationRepository
			.findAllBySellTradeIdOrderByLotExecutedAtAscLotIdAsc(sellTradeId)) {
			buyTradeIds.add(allocation.getHoldingLot().getBuyTrade().getId());
		}
		return List.copyOf(buyTradeIds);
	}

	// 코인 체결은 재생세션이 없어 null이다 (Trade가 그것을 강제한다).
	private LocalDate sourceTradingDateOf(HoldingLot lot) {
		Trade buyTrade = lot.getBuyTrade();
		return buyTrade.getStockReplaySession() == null
			? null
			: buyTrade.getStockReplaySession().getSourceTradingDate();
	}
}
