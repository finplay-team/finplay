// 인증 사용자의 관심목록 등록·조회·해제를 처리하는 서비스
package com.finplay.api.domain.watchlist.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.watchlist.dto.response.WatchlistItemListResponse;
import com.finplay.api.domain.watchlist.dto.response.WatchlistItemResponse;
import com.finplay.api.domain.watchlist.entity.WatchlistItem;
import com.finplay.api.domain.watchlist.repository.WatchlistItemRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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
		if (watchlistItemRepository.existsByUserIdAndInstrumentId(userId, instrumentId)) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}
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
