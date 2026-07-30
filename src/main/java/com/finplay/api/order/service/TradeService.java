// 계좌 소유권+시장 스코프를 검증하고 체결 내역을 커서 페이지네이션으로 조회하는 서비스
package com.finplay.api.order.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.dto.response.TradeListItemResponse;
import com.finplay.api.order.dto.response.TradeListResponse;
import com.finplay.api.order.repository.TradeRepository;
import java.util.List;
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
}
