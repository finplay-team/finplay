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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TradeService {

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

	// 이 계좌에 매도 체결 이력이 있는가 — GET /api/rankings/me의 status 판정용(이슈 #279).
	@Transactional(readOnly = true)
	public boolean hasSellHistory(Long accountId) {
		return tradeRepository.existsByAccountIdAndSide(accountId, OrderSide.SELL);
	}

	// 이 시장에 매도 체결 이력이 있는 계좌가 하나라도 있는가 — GET /api/rankings의 status 판정용(이슈 #279).
	@Transactional(readOnly = true)
	public boolean hasAnySellHistory(Market market) {
		return tradeRepository.existsBySideAndAccountMarket(OrderSide.SELL, market);
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
}
