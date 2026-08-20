// 계좌 소유권+시장 스코프를 검증하고 체결 내역을 커서 페이지네이션으로 조회하는 서비스
package com.finplay.api.order.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.dto.response.TradeListItemResponse;
import com.finplay.api.order.dto.response.TradeListResponse;
import com.finplay.api.order.repository.TradeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TradeService {

	// 튜토리얼 실행 요약의 평균 체결가 정밀도. trades.price·holding_lots.unit_cost의 scale과 같다.
	private static final int PRACTICE_PRICE_SCALE = 8;

	private final AccountService accountService;
	private final TradeRepository tradeRepository;

	@Transactional(readOnly = true)
	public TradeListResponse getMyTrades(Long userId, Market market, String cursor, int limit) {
		Account account = accountService.getAccountFor(userId, market);
		TradeCursor parsedCursor = TradeCursor.parse(cursor);

		List<Trade> fetched = tradeRepository.findByAccountIdWithCursor(
			account.getId(),
			parsedCursor == null ? null : parsedCursor.executedAt(),
			parsedCursor == null ? null : parsedCursor.id(),
			limit + 1);

		boolean hasNext = fetched.size() > limit;
		List<Trade> page = hasNext ? fetched.subList(0, limit) : fetched;
		String nextCursor = hasNext ? TradeCursor.encode(page.get(page.size() - 1)) : null;

		List<TradeListItemResponse> content = page.stream().map(TradeListItemResponse::from).toList();
		return TradeListResponse.of(content, nextCursor, hasNext);
	}

	@Transactional(readOnly = true)
	public Trade getOwnedTrade(Long userId, Long tradeId) {
		Trade trade = tradeRepository.findById(tradeId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (!trade.getAccount().getUser().getId().equals(userId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		return trade;
	}

	// 랭킹 재구성 대상 계좌 id(이슈 #279). ranking 도메인이 TradeRepository를 직접 주입하지 않도록 하는
	// 경유점이다(ADR-0002). OrderSide.SELL 리터럴도 이 위임 덕분에 order 도메인 밖으로 새지 않는다.
	@Transactional(readOnly = true)
	public List<Long> getSoldAccountIds(Market market) {
		return tradeRepository.findDistinctAccountIdsBySideAndMarket(OrderSide.SELL, market);
	}

	// 이 계좌에 실제 종목 매도 체결 이력이 있는가 — GET /api/rankings/me의 status 판정용(이슈 #279, #366).
	@Transactional(readOnly = true)
	public boolean hasSellHistory(Long accountId) {
		return tradeRepository.existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(accountId, OrderSide.SELL);
	}

	// 이 시장에 실제 종목 매도 체결 이력이 있는 계좌가 하나라도 있는가 — GET /api/rankings의 status
	// 판정용(이슈 #279, #366).
	@Transactional(readOnly = true)
	public boolean hasAnySellHistory(Market market) {
		return tradeRepository.existsBySideAndAccountMarketAndInstrument_TutorialSampleFalse(OrderSide.SELL, market);
	}

	// 026-market-order-practice-tutorial 2단계 chain 해석용 — intention.createdAt 이후 체결된 본인 BUY 체결 중
	// intention 수량과 정규화 비교(BigDecimal.compareTo, 020의 scale 무관 규칙)로 일치하는 가장 이른 체결 1건을
	// 고른다. education 도메인은 이 서비스 메서드로만 체결을 조회하고 TradeRepository를 직접 주입하지 않는다
	// (ADR-0002).
	@Transactional(readOnly = true)
	public Optional<Trade> findEarliestFilledBuyTradeMatching(
		Long userId, Long instrumentId, BigDecimal quantity, LocalDateTime after) {
		return tradeRepository
			.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
				userId, instrumentId, OrderSide.BUY, after)
			.stream()
			.filter(trade -> trade.getQuantity().compareTo(quantity) == 0)
			.findFirst();
	}

	// 030 holding 관찰 세션 가격원 역추적용(이슈 #321) — buyTrade가 귀속된 order의 practicePriceSessionId를
	// 공개 조회로 노출한다. education 도메인은 이 메서드로만 세션 귀속을 확인하고 OrderRepository를 직접
	// 주입하지 않는다(ADR-0002). trade가 세션 없는 실제 가격 주문이면 빈 값을 반환한다.
	@Transactional(readOnly = true)
	public Optional<Long> findPracticePriceSessionId(Long buyTradeId) {
		return tradeRepository.findPracticePriceSessionIdByTradeId(buyTradeId);
	}

	// 031-tutorial-sandbox-instruments 매도 chain 해석용 — findEarliestFilledBuyTradeMatching과 대칭이다.
	// buyTrade.executedAt 이후 체결된 본인 SELL 체결 중 executedAt ASC, tradeId ASC로 가장 이른 것 1건을
	// 고른다. 수량 일치는 요구하지 않는다(holding 전량이 아니라 일부만 팔아도 매도 실행 사실은 성립).
	@Transactional(readOnly = true)
	public Optional<Trade> findEarliestFilledSellTradeAfter(Long userId, Long instrumentId, LocalDateTime after) {
		return tradeRepository
			.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
				userId, instrumentId, OrderSide.SELL, after)
			.stream()
			.findFirst();
	}

	// 이슈 #339 통합 테스트 중 발견한 회귀 수정 — 샘플 종목 chain 재도전용. findEarliestFilledBuyTradeMatching과
	// 같은 정렬(executedAt ASC, tradeId ASC) 조회 결과에서 마지막(가장 최신) 수량 일치 체결을 고른다. 실제 종목
	// chain의 anti-gaming 규칙(가장 이른 체결 고정, 026)은 이 메서드를 쓰지 않으므로 그대로 유지된다 — 호출부
	// (MarketPracticeChainResolutionService)가 isTutorialSample()로 분기한다.
	@Transactional(readOnly = true)
	public Optional<Trade> findLatestFilledBuyTradeMatching(
		Long userId, Long instrumentId, BigDecimal quantity, LocalDateTime after) {
		return tradeRepository
			.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
				userId, instrumentId, OrderSide.BUY, after)
			.stream()
			.filter(trade -> trade.getQuantity().compareTo(quantity) == 0)
			.reduce((first, second) -> second);
	}

	/**
	 * 현재 실행 세대의 순보유수량(FILLED BUY − FILLED SELL). <b>042 EXITPRESET-003의 프리셋 잠금,
	 * EXITPRESET-020의 진입당 1회 가드, 041의 대기 구간 탈출 판정이 모두 이 한 메서드를 쓴다</b>
	 * (042 plan §자동 예약 생성 "구현에서 한 메서드로 모은다").
	 *
	 * <p><b>holdings 행의 수량을 쓰지 않는 이유</b>는 그것이 실행 세대를 넘어 누적되기 때문이다. 재시작이
	 * 청산하지 못한 이전 보유(attempt 도입 전에 만들어진 holding 등)가 남아 있으면, 새 실행의 첫 매수인데도
	 * "이미 들고 있다"로 판정돼 기준선이 만들어지지 않는다.
	 */
	@Transactional(readOnly = true)
	public BigDecimal netFilledQuantity(Long attemptId, long runNumber) {
		BigDecimal net = tradeRepository.sumNetFilledPracticeRunQuantity(attemptId, runNumber);
		return net == null ? BigDecimal.ZERO : net;
	}

	// 041 대기 구간 탈출용 — 진행 계산이 delta를 자를 기준 시각 하나만 읽는다(체결 목록 전체를 읽지 않는다).
	@Transactional(readOnly = true)
	public Optional<LocalDateTime> findLatestPracticeRunBuyExecutedAt(Long attemptId, long runNumber) {
		return tradeRepository.findLatestPracticeRunBuyExecutedAt(attemptId, runNumber);
	}

	// 이슈 #421: 수량 합계와 같은 한 번의 순회에서 이번 실행의 체결가·실현손익까지 집계한다. 별도 조회를
	// 더하지 않으므로 기존 호출부의 쿼리 수는 그대로다.
	@Transactional(readOnly = true)
	public PracticeRunTradeSummaryDto summarizePracticeRun(Long attemptId, long runNumber) {
		BigDecimal buyQuantity = BigDecimal.ZERO;
		BigDecimal sellQuantity = BigDecimal.ZERO;
		BigDecimal buyNotional = BigDecimal.ZERO;
		BigDecimal sellNotional = BigDecimal.ZERO;
		long realizedPnl = 0L;
		long soldBuyBasis = 0L;
		boolean realizedPnlComplete = true;
		Trade firstSell = null;
		for (Trade trade : tradeRepository.findFilledPracticeRunTrades(attemptId, runNumber)) {
			if (trade.getSide() == OrderSide.BUY) {
				buyQuantity = buyQuantity.add(trade.getQuantity());
				buyNotional = buyNotional.add(trade.getPrice().multiply(trade.getQuantity()));
			} else {
				sellQuantity = sellQuantity.add(trade.getQuantity());
				sellNotional = sellNotional.add(trade.getPrice().multiply(trade.getQuantity()));
				if (trade.getRealizedPnl() == null) {
					realizedPnlComplete = false;
				} else {
					realizedPnl += trade.getRealizedPnl();
					// PortfolioSellService.finalizeSellRealizedPnl이 확정한
					// realizedPnl = (amount - fee) - buyBasis를 그대로 되돌린 값이다. trade_allocations를 다시
					// 읽지 않고도 수익률의 분모(배분 매수원가 + 배분 매수수수료)를 원장과 정확히 같은 값으로
					// 얻는다 — 같은 식의 역이라 두 값이 갈라질 수 없다.
					soldBuyBasis += (trade.getAmount() - trade.getFee()) - trade.getRealizedPnl();
				}
				if (firstSell == null) {
					firstSell = trade;
				}
			}
		}
		BigDecimal netQuantity = buyQuantity.subtract(sellQuantity);
		boolean sold = sellQuantity.signum() > 0 && realizedPnlComplete;
		return new PracticeRunTradeSummaryDto(
			buyQuantity,
			sellQuantity,
			netQuantity.max(BigDecimal.ZERO),
			firstSell,
			averagePrice(buyNotional, buyQuantity),
			averagePrice(sellNotional, sellQuantity),
			sold ? Long.valueOf(realizedPnl) : null,
			sold ? Long.valueOf(soldBuyBasis) : null);
	}

	// Holding.averagePrice·SellAllocationQueryService의 매수단가와 같은 scale 8 HALF_UP이다. 체결이 1건이면
	// 나눗셈이 원래 단가를 그대로 돌려준다.
	private BigDecimal averagePrice(BigDecimal notional, BigDecimal quantity) {
		if (quantity.signum() <= 0) {
			return null;
		}
		return notional.divide(quantity, PRACTICE_PRICE_SCALE, RoundingMode.HALF_UP);
	}
}
