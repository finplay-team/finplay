// 매수 체결에 대한 투자일기 작성 유스케이스를 처리하는 서비스
package com.finplay.api.journal.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.journal.domain.BuyTradeJournal;
import com.finplay.api.journal.domain.SellTradeJournal;
import com.finplay.api.journal.dto.response.BuyJournalResponse;
import com.finplay.api.journal.dto.response.SellJournalResponse;
import com.finplay.api.journal.dto.response.SellJournalUpdateResponse;
import com.finplay.api.journal.repository.BuyTradeJournalRepository;
import com.finplay.api.journal.repository.SellTradeJournalRepository;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.TradeService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JournalService {

	private final TradeService tradeService;
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
}
