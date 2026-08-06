// 관심목록 등록·조회·해제와 중복 등록 예외 변환의 성공·실패 분기를 검증하는 단위 테스트다.
package com.finplay.api.watchlist.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.watchlist.domain.WatchlistItem;
import com.finplay.api.watchlist.dto.response.WatchlistItemListResponse;
import com.finplay.api.watchlist.dto.response.WatchlistItemResponse;
import com.finplay.api.watchlist.repository.WatchlistItemRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class WatchlistServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-06T01:00:00Z");
	private static final Long USER_ID = 7L;

	private InstrumentService instrumentService;
	private WatchlistItemRepository watchlistItemRepository;
	private WatchlistService watchlistService;

	@BeforeEach
	void setUp() {
		instrumentService = mock(InstrumentService.class);
		watchlistItemRepository = mock(WatchlistItemRepository.class);
		watchlistService = new WatchlistService(
			instrumentService, watchlistItemRepository, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void createWatchlistItemReturnsSavedInstrumentFieldsAndCurrentTimestamp() {
		Instrument instrument = instrument(10L, "005930", "삼성전자");
		when(instrumentService.getInstrumentEntity(10L)).thenReturn(instrument);
		when(watchlistItemRepository.saveAndFlush(any(WatchlistItem.class))).thenAnswer(invocation -> {
			WatchlistItem saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 100L);
			return saved;
		});

		WatchlistItemResponse result = watchlistService.createWatchlistItem(USER_ID, 10L);

		assertThat(result.watchlistItemId()).isEqualTo(100L);
		assertThat(result.instrumentId()).isEqualTo(10L);
		assertThat(result.market()).isEqualTo("STOCK");
		assertThat(result.symbol()).isEqualTo("005930");
		assertThat(result.name()).isEqualTo("삼성전자");
		assertThat(result.createdAt()).isEqualTo(now());
	}

	@Test
	void createWatchlistItemPropagatesInstrumentNotFound() {
		BusinessException notFound = new BusinessException(ErrorCode.NOT_FOUND);
		when(instrumentService.getInstrumentEntity(10L)).thenThrow(notFound);

		assertThatThrownBy(() -> watchlistService.createWatchlistItem(USER_ID, 10L)).isSameAs(notFound);
	}

	@Test
	void createWatchlistItemConvertsDataIntegrityViolationToDuplicateResource() {
		when(instrumentService.getInstrumentEntity(10L)).thenReturn(instrument(10L, "005930", "삼성전자"));
		when(watchlistItemRepository.saveAndFlush(any(WatchlistItem.class)))
			.thenThrow(new DataIntegrityViolationException("duplicate key"));

		assertThatThrownBy(() -> watchlistService.createWatchlistItem(USER_ID, 10L))
			.isInstanceOf(BusinessException.class)
			.satisfies(error -> assertThat(((BusinessException)error).getErrorCode())
				.isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
	}

	@Test
	void getWatchlistItemsReturnsAllWhenMarketIsNull() {
		WatchlistItem item = watchlistItem(1L, instrument(10L, "005930", "삼성전자"));
		when(watchlistItemRepository.findByUserIdOrderByCreatedAtDescIdDesc(USER_ID))
			.thenReturn(List.of(item));

		WatchlistItemListResponse result = watchlistService.getWatchlistItems(USER_ID, null);

		assertThat(result.content()).extracting(WatchlistItemResponse::instrumentId).containsExactly(10L);
	}

	@Test
	void getWatchlistItemsFiltersByMarketWhenProvided() {
		WatchlistItem item = watchlistItem(1L, instrument(20L, "BTC", "비트코인"));
		when(watchlistItemRepository.findByUserIdAndInstrument_MarketOrderByCreatedAtDescIdDesc(USER_ID, Market.CRYPTO))
			.thenReturn(List.of(item));

		WatchlistItemListResponse result = watchlistService.getWatchlistItems(USER_ID, Market.CRYPTO);

		assertThat(result.content()).extracting(WatchlistItemResponse::instrumentId).containsExactly(20L);
	}

	@Test
	void getWatchlistItemsReturnsEmptyListWhenUserHasNone() {
		when(watchlistItemRepository.findByUserIdOrderByCreatedAtDescIdDesc(USER_ID)).thenReturn(List.of());

		WatchlistItemListResponse result = watchlistService.getWatchlistItems(USER_ID, null);

		assertThat(result.content()).isEmpty();
	}

	@Test
	void deleteWatchlistItemRemovesOwnedItem() {
		WatchlistItem item = watchlistItem(1L, instrument(10L, "005930", "삼성전자"));
		when(watchlistItemRepository.findByUserIdAndInstrumentId(USER_ID, 10L)).thenReturn(Optional.of(item));

		watchlistService.deleteWatchlistItem(USER_ID, 10L);

		org.mockito.Mockito.verify(watchlistItemRepository).delete(item);
	}

	@Test
	void deleteWatchlistItemFailsWithWatchlistItemNotFoundWhenMissingOrNotOwned() {
		when(watchlistItemRepository.findByUserIdAndInstrumentId(USER_ID, 10L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> watchlistService.deleteWatchlistItem(USER_ID, 10L))
			.isInstanceOf(BusinessException.class)
			.satisfies(error -> assertThat(((BusinessException)error).getErrorCode())
				.isEqualTo(ErrorCode.WATCHLIST_ITEM_NOT_FOUND));
	}

	private WatchlistItem watchlistItem(long id, Instrument instrument) {
		WatchlistItem item = WatchlistItem.create(USER_ID, instrument, now());
		ReflectionTestUtils.setField(item, "id", id);
		return item;
	}

	private Instrument instrument(long id, String symbol, String name) {
		Instrument instrument = Instrument.create(
			symbol.equals("BTC") ? Market.CRYPTO : Market.STOCK, symbol, name, BigDecimal.ONE, 1L, true, now());
		ReflectionTestUtils.setField(instrument, "id", id);
		return instrument;
	}

	private LocalDateTime now() {
		return LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
	}
}
