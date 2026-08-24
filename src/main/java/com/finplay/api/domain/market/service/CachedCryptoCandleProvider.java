// 코인 interval=1m 캔들 조회를 Redis 캐시 우선으로 바꾸는 CryptoCandleProvider 데코레이터 — 캐시에 없는 구간만 빗썸 REST(BithumbRestCandleProvider)에 위임한다 (MKT-010)
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
		// 위임 구간의 끝을 요청 끝(effectiveTo)으로 클램프한다 — 요청 구간 전체가 since보다 과거이면
		// (매도 회고처럼 언제나 과거를 조회하는 경로) 클램프 없이는 to가 since-1분이 되고, 빗썸이 그 시각
		// 기준 최근 200봉을 돌려줘 요청과 겹치지 않는 구간이 온다. 호출부가 [from, to]로 다시 거르므로
		// 예외도 로그도 없이 결과가 통째로 빈다 (PR #281 리뷰).
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
				// Redis 단일 장애점이 되지 않는다 — 이 구간만 빗썸으로 넘긴다(plan.md "Redis 조회 실패").
				log.warn("코인 분봉 캐시 조회 실패, 해당 구간은 빗썸에 위임합니다: symbol={}", symbol, ex);
				cached = delegate.getCandles(symbol, interval, cacheFrom, effectiveTo);
			}
		}

		List<CryptoCandleDto> merged = merge(delegated, cached);
		// D-2(048): 캐시가 담당한 구간은 체결 없는 분의 키가 없어(027) 200분 창이어도 200개에 못 미칠 수
		// 있다. hasNext는 개수(size==200)로만 판정하므로(CandleQueryService), 여기서 못 채우면 과거가
		// 남았는데도 페이징이 조기 종료된다. 요청 창이 정확히 200분 폭일 때만 같은 구간을 빗썸에 한 번 더
		// 위임해 채운다 — 위임은 존재하는 봉만 count개까지 채워 오므로(9-1) 이 보충이 200개를 채운다.
		boolean fullWidthWindow = ChronoUnit.MINUTES.between(effectiveFrom, effectiveTo) == MAX_COUNT - 1;
		if (merged.size() < MAX_COUNT && fullWidthWindow) {
			List<CryptoCandleDto> supplement = delegate.getCandles(symbol, interval, effectiveFrom, effectiveTo);
			// merge(a, b)는 겹치면 b를 채택한다 — merged(캐시 우선순위가 이미 반영된 값)를 뒤에 둬 027의
			// "겹치면 캐시 봉이 이긴다" 우선순위를 보충 이후에도 그대로 유지한다.
			merged = merge(supplement, merged);
		}
		if (merged.size() > MAX_COUNT) {
			// 캐시 전용 진행 중 봉이 보충 200개에 더해져 넘칠 수 있어 최신 200개만 남긴다.
			merged = merged.subList(merged.size() - MAX_COUNT, merged.size());
		}
		return merged;
	}

	private static LocalDateTime min(LocalDateTime left, LocalDateTime right) {
		return left.isAfter(right) ? right : left;
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
