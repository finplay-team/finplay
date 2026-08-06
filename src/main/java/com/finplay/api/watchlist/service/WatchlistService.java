// 인증 사용자의 관심목록 등록·조회·해제를 처리하는 서비스
package com.finplay.api.watchlist.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.watchlist.domain.WatchlistItem;
import com.finplay.api.watchlist.dto.response.WatchlistItemListResponse;
import com.finplay.api.watchlist.dto.response.WatchlistItemResponse;
import com.finplay.api.watchlist.repository.WatchlistItemRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WatchlistService {

	private final InstrumentService instrumentService;
	private final WatchlistItemRepository watchlistItemRepository;
	private final Clock clock;

	@Transactional
	public WatchlistItemResponse createWatchlistItem(Long userId, Long instrumentId) {
		Instrument instrument = instrumentService.getInstrumentEntity(instrumentId);
		WatchlistItem watchlistItem = WatchlistItem.create(userId, instrument, LocalDateTime.now(clock));
		try {
			return WatchlistItemResponse.from(watchlistItemRepository.saveAndFlush(watchlistItem));
		} catch (DataIntegrityViolationException concurrentDuplicate) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}
	}

	@Transactional(readOnly = true)
	public WatchlistItemListResponse getWatchlistItems(Long userId, Market market) {
		List<WatchlistItem> watchlistItems = market == null
			? watchlistItemRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId)
			: watchlistItemRepository.findByUserIdAndInstrument_MarketOrderByCreatedAtDescIdDesc(userId, market);
		return WatchlistItemListResponse.from(watchlistItems);
	}

	@Transactional
	public void deleteWatchlistItem(Long userId, Long instrumentId) {
		WatchlistItem watchlistItem = watchlistItemRepository
			.findByUserIdAndInstrumentId(userId, instrumentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.WATCHLIST_ITEM_NOT_FOUND));
		watchlistItemRepository.delete(watchlistItem);
	}
}
