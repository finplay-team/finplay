// 코인 interval=1m 캔들 조회를 Redis 캐시 우선으로 바꾸는 CryptoCandleProvider 데코레이터 — 캐시에 없는 구간만 빗썸 REST(BithumbRestCandleProvider)에 위임한다 (MKT-010)
package com.finplay.api.market.service;

import com.finplay.api.market.store.CryptoCandleStore;
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

// BithumbRestCandleProvider(위임 대상)를 구체 클래스로 직접 주입받는다 — CryptoCandleProvider 인터페이스로
// 주입하면 이 빈이 @Primary라 자기 자신이 주입되는 순환이 생긴다(plan.md "빈 배선"). CandleQueryService는
// CryptoCandleProvider 인터페이스만 알기 때문에 이 데코레이터가 끼워져도 그쪽은 한 줄도 바뀌지 않는다.
@Slf4j
@Component
@Primary
@Profile({"prod", "crypto-real"})
@RequiredArgsConstructor
public class CachedCryptoCandleProvider implements CryptoCandleProvider {

	// 응답 상한 200봉과 같다 — CryptoCandleStore의 TTL 계산과 같은 계약에서 유도한 값이다.
	private static final int MAX_COUNT = 200;

	private final BithumbRestCandleProvider delegate;
	private final CryptoCandleStore candleStore;
	private final Clock clock;

	@Override
	public List<CryptoCandleDto> getCandles(String symbol, CandleInterval interval, LocalDateTime from,
		LocalDateTime to) {
		// 일·주·월봉은 이번 범위가 아니다 — 계속 빗썸 위임 그대로다(spec "범위 제외").
		if (interval != CandleInterval.ONE_MINUTE) {
			return delegate.getCandles(symbol, interval, from, to);
		}

		LocalDateTime effectiveTo = to != null ? to : LocalDateTime.now(clock);
		LocalDateTime effectiveFrom = from != null ? from : effectiveTo.minusMinutes(MAX_COUNT - 1);
		if (effectiveFrom.isAfter(effectiveTo)) {
			// from > to는 이 클래스의 관심사가 아니다 — 원래 계약대로 위임해 기존 오류 처리를 그대로 태운다.
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
		// since가 없으면(연결된 적 없음·캐시 비어있음) 캐시를 아예 쓰지 않고 전량 위임한다(plan.md "since 워터마크").
		if (since.isEmpty()) {
			return delegate.getCandles(symbol, interval, effectiveFrom, effectiveTo);
		}

		LocalDateTime sinceValue = since.get();
		LocalDateTime cacheFrom = effectiveFrom.isAfter(sinceValue) ? effectiveFrom : sinceValue;
		LocalDateTime delegateTo = sinceValue.minusMinutes(1);

		List<CryptoCandleDto> delegated = List.of();
		if (!effectiveFrom.isAfter(delegateTo)) {
			delegated = delegate.getCandles(symbol, interval, effectiveFrom, delegateTo);
		}

		List<CryptoCandleDto> cached = List.of();
		if (!cacheFrom.isAfter(effectiveTo)) {
			try {
				cached = candleStore.getCandles(symbol, cacheFrom, effectiveTo);
			} catch (Exception ex) {
				// Redis 단일 장애점이 되지 않는다 — 이 구간만 빗썸으로 넘긴다(plan.md "Redis 조회 실패").
				log.warn("코인 분봉 캐시 조회 실패, 해당 구간은 빗썸에 위임합니다: symbol={}", symbol, ex);
				cached = delegate.getCandles(symbol, interval, cacheFrom, effectiveTo);
			}
		}

		return merge(delegated, cached);
	}

	// 겹치는 sourceTime이 있으면 캐시(cached) 쪽을 채택한다 — 우리 데이터가 진행 중 분봉을 담고 있어 더
	// 최신이다(spec "캐시 구간과 빗썸 구간을 이어붙일 때... 겹치면 우리 봉을 우선한다").
	private List<CryptoCandleDto> merge(List<CryptoCandleDto> delegated, List<CryptoCandleDto> cached) {
		TreeMap<LocalDateTime, CryptoCandleDto> byTime = new TreeMap<>();
		delegated.forEach(candle -> byTime.put(candle.sourceTime(), candle));
		cached.forEach(candle -> byTime.put(candle.sourceTime(), candle));
		return new ArrayList<>(byTime.values());
	}
}
