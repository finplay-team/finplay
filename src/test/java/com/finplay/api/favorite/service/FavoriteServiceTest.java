// 즐겨찾기 등록의 성공과 비즈니스·유일 제약 실패 분기를 검증하는 단위 테스트다.
package com.finplay.api.favorite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.favorite.domain.Favorite;
import com.finplay.api.favorite.dto.response.FavoriteListResponse;
import com.finplay.api.favorite.dto.response.FavoriteResponse;
import com.finplay.api.favorite.repository.FavoriteRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class FavoriteServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-03T01:00:00Z");
	private FavoriteRepository favoriteRepository;
	private InstrumentService instrumentService;
	private UserQueryService userQueryService;
	private FavoriteService favoriteService;

	@BeforeEach
	void setUp() {
		favoriteRepository = mock(FavoriteRepository.class);
		instrumentService = mock(InstrumentService.class);
		userQueryService = mock(UserQueryService.class);
		favoriteService = new FavoriteService(
			favoriteRepository, instrumentService, userQueryService, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void getFavoritesReturnsActualFieldsWithoutWriting() {
		Instrument instrument = mock(Instrument.class);
		Favorite favorite = mock(Favorite.class);
		when(instrument.getId()).thenReturn(10L);
		when(instrument.getMarket()).thenReturn(Market.STOCK);
		when(instrument.getSymbol()).thenReturn("005930");
		when(instrument.getName()).thenReturn("삼성전자");
		when(favorite.getId()).thenReturn(99L);
		when(favorite.getInstrument()).thenReturn(instrument);
		when(favorite.getCreatedAt()).thenReturn(now());
		when(favoriteRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(7L))
			.thenReturn(List.of(favorite));

		FavoriteListResponse result = favoriteService.getFavorites(7L);

		assertThat(result.content()).containsExactly(new FavoriteResponse(
			99L, 10L, "STOCK", "005930", "삼성전자", now()));
		verify(favoriteRepository).findAllByUserIdOrderByCreatedAtDescIdDesc(7L);
		verifyNoMoreInteractions(favoriteRepository);
		verifyNoInteractions(instrumentService, userQueryService);
	}

	@Test
	void createFavoriteReturnsSavedInstrumentFields() {
		Instrument instrument = instrument(true);
		User user = User.create("user@finplay.com", "hash", "user", now());
		Favorite saved = mock(Favorite.class);
		when(instrumentService.getInstrumentEntity(10L)).thenReturn(instrument);
		when(userQueryService.getUser(7L)).thenReturn(user);
		when(saved.getId()).thenReturn(99L);
		when(saved.getInstrument()).thenReturn(instrument);
		when(saved.getCreatedAt()).thenReturn(now());
		when(favoriteRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(Favorite.class))).thenReturn(saved);

		FavoriteResponse result = favoriteService.createFavorite(7L, 10L);

		assertThat(result.favoriteId()).isEqualTo(99L);
		assertThat(result.market()).isEqualTo("STOCK");
		assertThat(result.symbol()).isEqualTo("005930");
		assertThat(result.name()).isEqualTo("삼성전자");
		assertThat(result.createdAt()).isEqualTo(now());
	}

	@Test
	void createFavoritePropagatesInstrumentNotFound() {
		BusinessException notFound = new BusinessException(ErrorCode.NOT_FOUND);
		when(instrumentService.getInstrumentEntity(10L)).thenThrow(notFound);

		assertThatThrownBy(() -> favoriteService.createFavorite(7L, 10L)).isSameAs(notFound);
		verify(favoriteRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void createFavoriteRejectsNonTradableInstrument() {
		when(instrumentService.getInstrumentEntity(10L)).thenReturn(instrument(false));

		assertThatThrownBy(() -> favoriteService.createFavorite(7L, 10L))
			.isInstanceOf(BusinessException.class)
			.satisfies(error -> assertThat(((BusinessException)error).getErrorCode())
				.isEqualTo(ErrorCode.INSTRUMENT_NOT_TRADABLE));
		verify(userQueryService, never()).getUser(7L);
	}

	@Test
	void createFavoriteRejectsDuplicateBeforeLoadingUser() {
		when(instrumentService.getInstrumentEntity(10L)).thenReturn(instrument(true));
		when(favoriteRepository.existsByUserIdAndInstrumentId(7L, 10L)).thenReturn(true);

		assertThatThrownBy(() -> favoriteService.createFavorite(7L, 10L))
			.isInstanceOf(BusinessException.class)
			.satisfies(error -> assertThat(((BusinessException)error).getErrorCode())
				.isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
		verify(userQueryService, never()).getUser(7L);
	}

	@Test
	void createFavoriteMapsUniqueConstraintRaceToDuplicateResource() {
		stubSaveFailure(new DataIntegrityViolationException(
			"insert failed", new RuntimeException("Duplicate entry for key 'uk_favorites_user_instrument'")));

		assertThatThrownBy(() -> favoriteService.createFavorite(7L, 10L))
			.isInstanceOf(BusinessException.class)
			.satisfies(error -> assertThat(((BusinessException)error).getErrorCode())
				.isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
	}

	@Test
	void createFavoriteRethrowsUnrelatedDataIntegrityViolation() {
		DataIntegrityViolationException failure = new DataIntegrityViolationException(
			"foreign key failed", new RuntimeException("fk_favorites_user"));
		stubSaveFailure(failure);

		assertThatThrownBy(() -> favoriteService.createFavorite(7L, 10L)).isSameAs(failure);
	}

	@Test
	void deleteFavoriteDeletesOnlyOwnedFavorite() {
		Favorite favorite = mock(Favorite.class);
		when(favoriteRepository.findByUserIdAndInstrumentIdForUpdate(7L, 10L))
			.thenReturn(Optional.of(favorite));

		favoriteService.deleteFavorite(7L, 10L);

		verify(favoriteRepository).findByUserIdAndInstrumentIdForUpdate(7L, 10L);
		verify(favoriteRepository).delete(favorite);
		verifyNoMoreInteractions(favoriteRepository);
		verifyNoInteractions(instrumentService, userQueryService);
	}

	@Test
	void deleteFavoriteFailsWithFavoriteNotFoundWhenFavoriteDoesNotExist() {
		when(favoriteRepository.findByUserIdAndInstrumentIdForUpdate(7L, 10L))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> favoriteService.deleteFavorite(7L, 10L))
			.isInstanceOf(BusinessException.class)
			.satisfies(error -> assertThat(((BusinessException)error).getErrorCode())
				.isEqualTo(ErrorCode.FAVORITE_NOT_FOUND));
		verify(favoriteRepository, never()).delete(org.mockito.ArgumentMatchers.any());
	}

	private void stubSaveFailure(DataIntegrityViolationException failure) {
		when(instrumentService.getInstrumentEntity(10L)).thenReturn(instrument(true));
		when(userQueryService.getUser(7L)).thenReturn(User.create("user@finplay.com", "hash", "user", now()));
		when(favoriteRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(Favorite.class))).thenThrow(failure);
	}

	private Instrument instrument(boolean tradable) {
		return Instrument.create(Market.STOCK, "005930", "삼성전자", java.math.BigDecimal.ONE, 1L, tradable, now());
	}

	private LocalDateTime now() {
		return LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
	}
}
