package com.finplay.api.domain.favorite.service;

import com.finplay.api.domain.favorite.dto.response.FavoriteListResponse;
import com.finplay.api.domain.favorite.dto.response.FavoriteResponse;
import com.finplay.api.domain.favorite.model.Favorite;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FavoriteService {

	private final InstrumentService instrumentService;
	private final Clock clock;

	private final Map<Long, Map<Long, Favorite>> favoritesByUser = new ConcurrentHashMap<>();
	private final Map<Long, ReentrantLock> locksByUser = new ConcurrentHashMap<>();
	private final AtomicLong favoriteIdSequence = new AtomicLong();

	public FavoriteListResponse getFavorites(Long userId) {
		List<Favorite> favorites = List.copyOf(favoritesByUser.getOrDefault(userId, Map.of()).values());
		List<Favorite> sorted = favorites.stream()
			.sorted(
				Comparator.comparing(Favorite::createdAt, Comparator.reverseOrder())
					.thenComparing(Favorite::favoriteId, Comparator.reverseOrder()))
			.toList();
		return new FavoriteListResponse(sorted.stream().map(FavoriteResponse::from).toList());
	}

	public FavoriteResponse createFavorite(Long userId, Long instrumentId) {
		Instrument instrument = instrumentService.getInstrumentEntity(instrumentId);
		if (!instrument.isTradable()) {
			throw new BusinessException(ErrorCode.INSTRUMENT_NOT_TRADABLE);
		}

		return withFavoriteLock(userId, instrumentId, () -> {
			Map<Long, Favorite> userFavorites = favoritesByUser.computeIfAbsent(
				userId, key -> new ConcurrentHashMap<>());
			if (userFavorites.containsKey(instrumentId)) {
				throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
			}
			Favorite favorite = Favorite.create(
				favoriteIdSequence.incrementAndGet(),
				userId,
				instrumentId,
				instrument.getMarket().name(),
				instrument.getSymbol(),
				instrument.getName(),
				LocalDateTime.now(clock));
			userFavorites.put(instrumentId, favorite);
			return FavoriteResponse.from(favorite);
		});
	}

	public void deleteFavorite(Long userId, Long instrumentId) {
		withFavoriteLock(userId, instrumentId, () -> {
			Map<Long, Favorite> userFavorites = favoritesByUser.get(userId);
			if (userFavorites == null || userFavorites.remove(instrumentId) == null) {
				throw new BusinessException(ErrorCode.FAVORITE_NOT_FOUND);
			}
			return null;
		});
	}

	public <T> T withFavoriteLock(Long userId, Long instrumentId, Supplier<T> action) {
		ReentrantLock lock = locksByUser.computeIfAbsent(userId, key -> new ReentrantLock());
		lock.lock();
		try {
			return action.get();
		} finally {
			lock.unlock();
		}
	}

	public boolean isFavorited(Long userId, Long instrumentId) {
		Map<Long, Favorite> userFavorites = favoritesByUser.get(userId);
		return userFavorites != null && userFavorites.containsKey(instrumentId);
	}
}
