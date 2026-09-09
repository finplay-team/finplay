package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.store.CryptoCandleStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Primary
@Profile({"prod", "crypto-real"})
@RequiredArgsConstructor
public class CachedCryptoCandleProvider implements CryptoCandleProvider {

	private static final int MAX_COUNT = 200;

	private final BithumbRestCandleProvider delegate;
	private final CryptoCandleStore candleStore;
	private final Clock clock;

	@Override
	public List<CryptoCandleDto> getCandles(String symbol, CandleInterval interval, LocalDateTime from,
		LocalDateTime to) {
		if (interval != CandleInterval.ONE_MINUTE) {
			return delegate.getCandles(symbol, interval, from, to);
		}

		LocalDateTime effectiveTo = to != null ? to : LocalDateTime.now(clock);
		LocalDateTime effectiveFrom = from != null ? from : effectiveTo.minusMinutes(MAX_COUNT - 1);
		if (effectiveFrom.isAfter(effectiveTo)) {
			return delegate.getCandles(symbol, interval, from, to);
		}
		if (ChronoUnit.MINUTES.between(effectiveFrom, effectiveTo) > MAX_COUNT - 1) {
			effectiveFrom = effectiveTo.minusMinutes(MAX_COUNT - 1);
		}

		Optional<LocalDateTime> since;
		try {
			since = candleStore.getSince(symbol);
		} catch (Exception ex) {
			log.warn("코인 분봉 캐시(since) 조회 실패, 전량 빗썸에 위임합니다: symbol={}", symbol, ex);
			return delegate.getCandles(symbol, interval, effectiveFrom, effectiveTo);
		}
		if (since.isEmpty()) {
			return delegate.getCandles(symbol, interval, effectiveFrom, effectiveTo);
		}

		LocalDateTime sinceValue = since.get();
		LocalDateTime cacheFrom = effectiveFrom.isAfter(sinceValue) ? effectiveFrom : sinceValue;
		LocalDateTime delegateTo = min(sinceValue.minusMinutes(1), effectiveTo);

		List<CryptoCandleDto> delegated = List.of();
		if (!effectiveFrom.isAfter(delegateTo)) {
			delegated = delegate.getCandles(symbol, interval, effectiveFrom, delegateTo);
		}

		List<CryptoCandleDto> cached = List.of();
		if (!cacheFrom.isAfter(effectiveTo)) {
			try {
				cached = candleStore.getCandles(symbol, cacheFrom, effectiveTo);
			} catch (Exception ex) {
				log.warn("코인 분봉 캐시 조회 실패, 해당 구간은 빗썸에 위임합니다: symbol={}", symbol, ex);
				cached = delegate.getCandles(symbol, interval, cacheFrom, effectiveTo);
			}
		}

		List<CryptoCandleDto> merged = merge(delegated, cached);
		boolean fullWidthWindow = ChronoUnit.MINUTES.between(effectiveFrom, effectiveTo) == MAX_COUNT - 1;
		if (merged.size() < MAX_COUNT && fullWidthWindow) {
			List<CryptoCandleDto> supplement = delegate.getCandles(symbol, interval, effectiveFrom, effectiveTo);
			merged = merge(supplement, merged);
		}
		if (merged.size() > MAX_COUNT) {
			merged = merged.subList(merged.size() - MAX_COUNT, merged.size());
		}
		return merged;
	}

	private static LocalDateTime min(LocalDateTime left, LocalDateTime right) {
		return left.isAfter(right) ? right : left;
	}

	private List<CryptoCandleDto> merge(List<CryptoCandleDto> delegated, List<CryptoCandleDto> cached) {
		TreeMap<LocalDateTime, CryptoCandleDto> byTime = new TreeMap<>();
		delegated.forEach(candle -> byTime.put(candle.sourceTime(), candle));
		cached.forEach(candle -> byTime.put(candle.sourceTime(), candle));
		return new ArrayList<>(byTime.values());
	}
}
