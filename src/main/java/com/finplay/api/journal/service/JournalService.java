// 매수 체결에 대한 투자일기 작성 유스케이스를 처리하는 서비스
package com.finplay.api.journal.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.journal.domain.BuyTradeJournal;
import com.finplay.api.journal.domain.SellTradeJournal;
import com.finplay.api.journal.dto.response.BuyJournalResponse;
import com.finplay.api.journal.dto.response.BuyJournalUpdateResponse;
import com.finplay.api.journal.dto.response.JournalListItemResponse;
import com.finplay.api.journal.dto.response.JournalListResponse;
import com.finplay.api.journal.dto.response.SellJournalResponse;
import com.finplay.api.journal.dto.response.SellJournalUpdateResponse;
import com.finplay.api.journal.repository.BuyTradeJournalRepository;
import com.finplay.api.journal.repository.SellTradeJournalRepository;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.TradeService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JournalService {

	private final TradeService tradeService;
	private final AccountService accountService;
	private final BuyTradeJournalRepository buyTradeJournalRepository;
	private final SellTradeJournalRepository sellTradeJournalRepository;
	private final Clock clock;

	@Transactional
	public BuyJournalResponse createBuyJournal(Long userId, Long buyTradeId, String content) {
		Trade trade = tradeService.getOwnedTrade(userId, buyTradeId);
		if (trade.getSide() != OrderSide.BUY) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		if (buyTradeJournalRepository.existsByBuyTradeId(buyTradeId)) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}

		BuyTradeJournal journal = BuyTradeJournal.of(trade, content, LocalDateTime.now(clock));
		try {
			return BuyJournalResponse.from(buyTradeJournalRepository.saveAndFlush(journal));
		} catch (DataIntegrityViolationException concurrentDuplicate) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}
	}

	@Transactional
	public SellJournalResponse createSellJournal(Long userId, Long sellTradeId, String content) {
		Trade trade = tradeService.getOwnedTrade(userId, sellTradeId);
		if (trade.getSide() != OrderSide.SELL) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		if (sellTradeJournalRepository.existsBySellTradeId(sellTradeId)) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}

		SellTradeJournal journal = SellTradeJournal.of(trade, content, LocalDateTime.now(clock));
		try {
			return SellJournalResponse.from(sellTradeJournalRepository.saveAndFlush(journal));
		} catch (DataIntegrityViolationException concurrentDuplicate) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}
	}

	@Transactional
	public SellJournalUpdateResponse updateSellJournal(Long userId, Long sellTradeId, String content) {
		Trade trade = tradeService.getOwnedTrade(userId, sellTradeId);
		if (trade.getSide() != OrderSide.SELL) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		SellTradeJournal journal = sellTradeJournalRepository
			.findBySellTradeId(sellTradeId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		journal.updateContent(content, LocalDateTime.now(clock));
		return SellJournalUpdateResponse.from(journal);
	}

	@Transactional
	public BuyJournalUpdateResponse updateBuyJournal(Long userId, Long buyTradeId, String content) {
		Trade trade = tradeService.getOwnedTrade(userId, buyTradeId);
		if (trade.getSide() != OrderSide.BUY) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		BuyTradeJournal journal = buyTradeJournalRepository
			.findByBuyTradeId(buyTradeId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		journal.updateContent(content, LocalDateTime.now(clock));
		return BuyJournalUpdateResponse.from(journal);
	}

	@Transactional(readOnly = true)
	public JournalListResponse getMyJournalEntries(Long userId, Market market, String cursor, int limit) {
		Account account = accountService.getAccountFor(userId, market);
		JournalCursor parsedCursor = JournalCursor.parse(cursor);
		LocalDateTime cursorCreatedAt = parsedCursor == null ? null : parsedCursor.createdAt();
		Long cursorTradeId = parsedCursor == null ? null : parsedCursor.tradeId();

		List<BuyTradeJournal> buyPage = buyTradeJournalRepository.findByAccountIdWithCursor(
			account.getId(), cursorCreatedAt, cursorTradeId, limit + 1);
		List<SellTradeJournal> sellPage = sellTradeJournalRepository.findByAccountIdWithCursor(
			account.getId(), cursorCreatedAt, cursorTradeId, limit + 1);

		List<JournalListItemResponse> merged = Stream
			.concat(buyPage.stream().map(JournalListItemResponse::from),
				sellPage.stream().map(JournalListItemResponse::from))
			.sorted(Comparator.comparing(JournalListItemResponse::createdAt)
				.thenComparing(JournalService::tradeIdOf)
				.reversed())
			.limit(limit + 1)
			.toList();

		boolean hasNext = merged.size() > limit;
		List<JournalListItemResponse> content = hasNext ? merged.subList(0, limit) : merged;
		String nextCursor = hasNext
			? JournalCursor.encode(content.get(content.size() - 1).createdAt(),
				tradeIdOf(content.get(content.size() - 1)))
			: null;

		return JournalListResponse.of(content, nextCursor, hasNext);
	}

	private static Long tradeIdOf(JournalListItemResponse item) {
		return item.buyTradeId() != null ? item.buyTradeId() : item.sellTradeId();
	}
}
