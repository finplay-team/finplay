// 인증 사용자의 거래 가능 종목 즐겨찾기 조회·등록·해제를 처리하는 서비스(#193: 인메모리 저장으로 전환)
package com.finplay.api.favorite.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.favorite.domain.Favorite;
import com.finplay.api.favorite.dto.response.FavoriteListResponse;
import com.finplay.api.favorite.dto.response.FavoriteResponse;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.service.InstrumentService;
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

/**
 * 즐겨찾기(favorite)와 사전 의도(practice intention)는 ADR-0012(#193)에 따라 DB 테이블이 아니라 이 `@Service`
 * 싱글턴 빈 내부 힙 메모리에 저장한다. 재시작 시 전부 초기화되며 다중 인스턴스 배포 간 공유되지 않는다.
 *
 * <p>저장 구조: {@code Map<userId, Map<instrumentId, Favorite>>} (양쪽 모두 {@link ConcurrentHashMap}).
 * favoriteId는 전역 {@link AtomicLong} 시퀀스로 채번한다(재시작 시 0부터 재시작).
 *
 * <p><b>락 관련 public 메서드 계약({@code PracticeIntentionService}가 사용)</b>
 * <ul>
 *   <li>{@link #withFavoriteLock(Long, Long, Supplier)} — 사용자 단위 {@link ReentrantLock}을 획득한 채
 *       {@code action}을 실행하고 결과를 반환한 뒤 락을 해제한다(finally). 락 범위는 사용자 단위이며
 *       instrumentId는 API 형태를 {@code plan.md}의 설계와 맞추기 위해 받되 현재 락 세분화 단위에는
 *       사용하지 않는다. 호출부는 <b>이미 DB 트랜잭션·행 잠금을 잡은 상태에서만</b> 이 락을 빌려야 한다 —
 *       plan.md의 잠금 순서(예: intention 생성의 {@code progress(DB) → favorite 락(in-memory)})는
 *       "DB 락을 먼저 잡고 그 트랜잭션 안에서 이 in-memory 락을 이어 잡는다"는 뜻이며, 반대로 이 락을 먼저
 *       잡고 그 안에서 새 DB 트랜잭션을 열면 지금은 없는 역순 잠금 경로가 생겨 교착 위험이 생긴다.
 *   <li>{@link #isFavorited(Long, Long)} — 락 없이 존재 여부만 확인한다. 호출부가 이미
 *       {@link #withFavoriteLock}의 {@code action} 안에서 호출해야 TOCTOU 없이 존재 확인과 후속 작업이
 *       원자적으로 이어진다.
 * </ul>
 */
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

	/**
	 * 사용자 단위 {@link ReentrantLock}을 획득한 채 {@code action}을 실행하고 결과를 반환한다. 실행 도중
	 * 발생한 예외(비즈니스 예외 포함)는 그대로 전파하며, 어느 경우든 finally에서 락을 해제한다. 다른
	 * 도메인 서비스는 이 메서드로만 favorite 락을 빌려 쓰고 {@link #locksByUser} 등 내부 락 객체를 직접
	 * 다루지 않는다(ADR-0002의 "다른 도메인 repository 직접 주입 금지" 원칙을 락에도 동일 적용).
	 */
	public <T> T withFavoriteLock(Long userId, Long instrumentId, Supplier<T> action) {
		ReentrantLock lock = locksByUser.computeIfAbsent(userId, key -> new ReentrantLock());
		lock.lock();
		try {
			return action.get();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * 해당 favorite 존재 여부만 확인한다. 락을 걸지 않으므로 TOCTOU를 피하려면 반드시
	 * {@link #withFavoriteLock}의 {@code action} 내부에서 호출해야 한다.
	 */
	public boolean isFavorited(Long userId, Long instrumentId) {
		Map<Long, Favorite> userFavorites = favoritesByUser.get(userId);
		return userFavorites != null && userFavorites.containsKey(instrumentId);
	}
}
