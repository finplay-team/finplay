package com.finplay.api.domain.favorite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.favorite.dto.response.FavoriteListResponse;
import com.finplay.api.domain.favorite.dto.response.FavoriteResponse;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FavoriteServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-03T01:00:00Z");
	private InstrumentService instrumentService;
	private FavoriteService favoriteService;

	@BeforeEach
	void setUp() {
		instrumentService = mock(InstrumentService.class);
		favoriteService = new FavoriteService(instrumentService, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void getFavoritesReturnsEmptyListWhenUserHasNone() {
		FavoriteListResponse result = favoriteService.getFavorites(7L);

		assertThat(result.content()).isEmpty();
	}

	@Test
	void getFavoritesReturnsOnlyOwnedFavoritesNewestFirstWithIdTieBreak() {
		when(instrumentService.getInstrumentEntity(10L)).thenReturn(instrument(10L, "005930", "삼성전자"));
		when(instrumentService.getInstrumentEntity(20L)).thenReturn(instrument(20L, "000660", "SK하이닉스"));
		when(instrumentService.getInstrumentEntity(30L)).thenReturn(instrument(30L, "035420", "NAVER"));

		FavoriteResponse older = favoriteService.createFavorite(7L, 10L);
		FavoriteResponse newer = favoriteService.createFavorite(7L, 20L);
		favoriteService.createFavorite(99L, 30L);

		FavoriteListResponse result = favoriteService.getFavorites(7L);

		assertThat(result.content()).extracting(FavoriteResponse::favoriteId)
			.containsExactly(newer.favoriteId(), older.favoriteId());
	}

	@Test
	void createFavoriteReturnsSavedInstrumentFieldsAndCurrentTimestamp() {
		when(instrumentService.getInstrumentEntity(10L)).thenReturn(instrument(10L, "005930", "삼성전자"));

		FavoriteResponse result = favoriteService.createFavorite(7L, 10L);

		assertThat(result.instrumentId()).isEqualTo(10L);
		assertThat(result.market()).isEqualTo("STOCK");
		assertThat(result.symbol()).isEqualTo("005930");
		assertThat(result.name()).isEqualTo("삼성전자");
		assertThat(result.createdAt()).isEqualTo(now());
		assertThat(favoriteService.isFavorited(7L, 10L)).isTrue();
	}

	@Test
	void createFavoriteAssignsIncreasingFavoriteIdsAcrossAllUsers() {
		when(instrumentService.getInstrumentEntity(10L)).thenReturn(instrument(10L, "005930", "삼성전자"));
		when(instrumentService.getInstrumentEntity(20L)).thenReturn(instrument(20L, "000660", "SK하이닉스"));

		FavoriteResponse first = favoriteService.createFavorite(7L, 10L);
		FavoriteResponse second = favoriteService.createFavorite(8L, 20L);

		assertThat(second.favoriteId()).isGreaterThan(first.favoriteId());
	}

	@Test
	void createFavoritePropagatesInstrumentNotFound() {
		BusinessException notFound = new BusinessException(ErrorCode.NOT_FOUND);
		when(instrumentService.getInstrumentEntity(10L)).thenThrow(notFound);

		assertThatThrownBy(() -> favoriteService.createFavorite(7L, 10L)).isSameAs(notFound);
		assertThat(favoriteService.isFavorited(7L, 10L)).isFalse();
	}

	@Test
	void createFavoriteRejectsNonTradableInstrument() {
		when(instrumentService.getInstrumentEntity(10L)).thenReturn(instrument(10L, "005930", "삼성전자", false));

		assertThatThrownBy(() -> favoriteService.createFavorite(7L, 10L))
			.isInstanceOf(BusinessException.class)
			.satisfies(error -> assertThat(((BusinessException)error).getErrorCode())
				.isEqualTo(ErrorCode.INSTRUMENT_NOT_TRADABLE));
		assertThat(favoriteService.isFavorited(7L, 10L)).isFalse();
	}

	@Test
	void createFavoriteRejectsDuplicateForSameUserAndInstrument() {
		when(instrumentService.getInstrumentEntity(10L)).thenReturn(instrument(10L, "005930", "삼성전자"));
		favoriteService.createFavorite(7L, 10L);

		assertThatThrownBy(() -> favoriteService.createFavorite(7L, 10L))
			.isInstanceOf(BusinessException.class)
			.satisfies(error -> assertThat(((BusinessException)error).getErrorCode())
				.isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
	}

	@Test
	void deleteFavoriteRemovesOnlyTargetedUserInstrumentPair() {
		when(instrumentService.getInstrumentEntity(10L)).thenReturn(instrument(10L, "005930", "삼성전자"));
		when(instrumentService.getInstrumentEntity(20L)).thenReturn(instrument(20L, "000660", "SK하이닉스"));
		favoriteService.createFavorite(7L, 10L);
		favoriteService.createFavorite(7L, 20L);

		favoriteService.deleteFavorite(7L, 10L);

		assertThat(favoriteService.isFavorited(7L, 10L)).isFalse();
		assertThat(favoriteService.isFavorited(7L, 20L)).isTrue();
	}

	@Test
	void deleteFavoriteFailsWithFavoriteNotFoundWhenNeverCreated() {
		assertThatThrownBy(() -> favoriteService.deleteFavorite(7L, 10L))
			.isInstanceOf(BusinessException.class)
			.satisfies(error -> assertThat(((BusinessException)error).getErrorCode())
				.isEqualTo(ErrorCode.FAVORITE_NOT_FOUND));
	}

	@Test
	void deleteFavoriteFailsWithFavoriteNotFoundWhenUserHasNoFavoritesAtAll() {
		assertThatThrownBy(() -> favoriteService.deleteFavorite(999L, 10L))
			.isInstanceOf(BusinessException.class)
			.satisfies(error -> assertThat(((BusinessException)error).getErrorCode())
				.isEqualTo(ErrorCode.FAVORITE_NOT_FOUND));
	}

	@Test
	void isFavoritedReturnsFalseForUnknownUser() {
		assertThat(favoriteService.isFavorited(12345L, 10L)).isFalse();
	}

	@Test
	void withFavoriteLockReturnsActionResultAndPropagatesException() {
		String result = favoriteService.withFavoriteLock(7L, 10L, () -> "value");
		assertThat(result).isEqualTo("value");

		RuntimeException failure = new RuntimeException("boom");
		assertThatThrownBy(() -> favoriteService.withFavoriteLock(7L, 10L, () -> {
			throw failure;
		})).isSameAs(failure);
	}

	private Instrument instrument(long id, String symbol, String name) {
		return instrument(id, symbol, name, true);
	}

	private Instrument instrument(long id, String symbol, String name, boolean tradable) {
		return Instrument.create(Market.STOCK, symbol, name, java.math.BigDecimal.ONE, 1L, tradable, now());
	}

	private LocalDateTime now() {
		return LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
	}
}
