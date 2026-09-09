package com.finplay.api.domain.order.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.dto.response.TradeListItemResponse;
import com.finplay.api.domain.order.dto.response.TradeListResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TradeService {

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

	@Transactional(readOnly = true)
	public List<Long> getSoldAccountIds(Market market) {
		return tradeRepository.findDistinctAccountIdsBySideAndMarket(OrderSide.SELL, market);
	}

	@Transactional(readOnly = true)
	public boolean hasSellHistory(Long accountId) {
		return tradeRepository.existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(accountId, OrderSide.SELL);
	}

	@Transactional(readOnly = true)
	public boolean hasAnySellHistory(Market market) {
		return tradeRepository.existsBySideAndAccountMarketAndInstrument_TutorialSampleFalse(OrderSide.SELL, market);
	}

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

	@Transactional(readOnly = true)
	public Optional<Long> findPracticePriceSessionId(Long buyTradeId) {
		return tradeRepository.findPracticePriceSessionIdByTradeId(buyTradeId);
	}

	@Transactional(readOnly = true)
	public Optional<Trade> findEarliestFilledSellTradeAfter(Long userId, Long instrumentId, LocalDateTime after) {
		return tradeRepository
			.findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
				userId, instrumentId, OrderSide.SELL, after)
			.stream()
			.findFirst();
	}

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

	@Transactional(readOnly = true)
	public BigDecimal netFilledQuantity(Long attemptId, long runNumber) {
		BigDecimal net = tradeRepository.sumNetFilledPracticeRunQuantity(attemptId, runNumber);
		return net == null ? BigDecimal.ZERO : net;
	}

	@Transactional(readOnly = true)
	public List<PracticeRunFillKindDto> findPracticeRunFillKinds(Long attemptId, long runNumber) {
		return tradeRepository.findPracticeRunFillKinds(attemptId, runNumber);
	}

	@Transactional(readOnly = true)
	public Optional<LocalDateTime> findLatestPracticeRunBuyExecutedAt(Long attemptId, long runNumber) {
		return tradeRepository.findLatestPracticeRunBuyExecutedAt(attemptId, runNumber);
	}

	@Transactional(readOnly = true)
	public PracticeRunTradeSummaryDto summarizePracticeRun(Long attemptId, long runNumber) {
		return summarize(tradeRepository.findFilledPracticeRunTrades(attemptId, runNumber));
	}

	@Transactional(readOnly = true)
	public List<PracticeRunTradeSummaryDto> summarizePracticeRunEntries(
		Long attemptId, long runNumber, List<Long> entryBuyTradeIds) {
		if (entryBuyTradeIds.isEmpty()) {
			return List.of();
		}
		for (int index = 1; index < entryBuyTradeIds.size(); index++) {
			if (entryBuyTradeIds.get(index) <= entryBuyTradeIds.get(index - 1)) {
				throw new IllegalArgumentException("진입 경계 체결 id가 오름차순이 아닙니다: " + entryBuyTradeIds);
			}
		}
		List<Trade> trades = tradeRepository.findFilledPracticeRunTrades(attemptId, runNumber);
		List<PracticeRunTradeSummaryDto> summaries = new ArrayList<>(entryBuyTradeIds.size());
		for (int index = 0; index < entryBuyTradeIds.size(); index++) {
			Long from = index == 0 ? null : entryBuyTradeIds.get(index);
			Long to = index + 1 < entryBuyTradeIds.size() ? entryBuyTradeIds.get(index + 1) : null;
			summaries.add(summarize(trades.stream()
				.filter(trade -> (from == null || trade.getId() >= from) && (to == null || trade.getId() < to))
				.toList()));
		}
		return List.copyOf(summaries);
	}

	private PracticeRunTradeSummaryDto summarize(List<Trade> trades) {
		BigDecimal buyQuantity = BigDecimal.ZERO;
		BigDecimal sellQuantity = BigDecimal.ZERO;
		BigDecimal buyNotional = BigDecimal.ZERO;
		BigDecimal sellNotional = BigDecimal.ZERO;
		long realizedPnl = 0L;
		long soldBuyBasis = 0L;
		boolean realizedPnlComplete = true;
		Trade firstSell = null;
		for (Trade trade : trades) {
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

	private BigDecimal averagePrice(BigDecimal notional, BigDecimal quantity) {
		if (quantity.signum() <= 0) {
			return null;
		}
		return notional.divide(quantity, PRACTICE_PRICE_SCALE, RoundingMode.HALF_UP);
	}
}
