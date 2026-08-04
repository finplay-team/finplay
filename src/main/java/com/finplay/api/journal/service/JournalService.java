// 매수 체결에 대한 투자일기 작성 유스케이스를 처리하는 서비스
package com.finplay.api.journal.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.journal.domain.BuyTradeJournal;
import com.finplay.api.journal.dto.response.BuyJournalResponse;
import com.finplay.api.journal.repository.BuyTradeJournalRepository;
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
}
