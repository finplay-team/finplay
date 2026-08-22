// 주식 분봉 수집 배치의 거래일 단위 Redis 락 — 키 접두사와 TTL만 정하고 획득·해제 메커니즘은 RedisLock에 맡긴다 (ADR-0014 선례, COLLECT-STAB-001)
package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.config.MarketStockProperties;
import com.finplay.api.global.lock.RedisLock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code KisHistoricalCandleCollector.collect()}가 대상 종목 조회 전부터 저장 완료까지 거래일 단위로 잠근다
 * (plan.md §락 설계 세부). 정규 08:10 배치와 재시도 실행이 같은 {@code tradingDate}를 다루므로 같은 키로 서로도
 * 배제한다.
 *
 * <p><b>SET NX PX 획득과 Lua check-then-delete 해제는 {@link RedisLock}에 위임한다</b>(ADR-0015 §4, ADR-0014
 * 선례 재사용). 이 클래스에 남는 것은 이 배치 전용 결정 둘뿐이다 — 키 접두사
 * {@code market:stock-collect:lock:}과 TTL {@code market.stock.collect-lock-ttl-seconds}(기본값 600초).
 * {@code CryptoWatchLock}을 직접 재사용하지 않는 이유도 같다 — 그 클래스의 키 접두사·TTL이 코인 감시 전용으로
 * 박혀 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockCollectionLock {

	private static final String KEY_PREFIX = "market:stock-collect:lock:";

	private final RedisLock redisLock;

	private final MarketStockProperties stockProperties;

	/**
	 * 주어진 거래일의 수집 락을 얻는다. 성공하면 이번 시도를 식별하는 토큰을 반환한다 — 해제할 때 그대로
	 * 넘겨야 한다. TTL은 {@code market.stock.collect-lock-ttl-seconds}다.
	 *
	 * <p>다른 인스턴스(또는 이전 실행)가 이미 이 거래일을 처리 중이거나, Redis 자체가 예외를 던지면(장애) 모두
	 * 획득 실패다 — 호출부는 이번 실행을 조용히 건너뛴다(COLLECT-STAB-001, 실패 이력으로 기록하지 않는다).
	 */
	public Optional<String> tryLock(LocalDate tradingDate) {
		return redisLock.tryLock(
			lockKey(tradingDate), Duration.ofSeconds(stockProperties.collectLockTtlSeconds()));
	}

	/**
	 * {@code tryLock}이 반환한 토큰으로만 해제한다. 지울 것이 없었으면(이미 TTL 만료 후 다른 인스턴스가 새로
	 * 잡은 락이면) {@code WARN}으로 남긴다 — TTL이 실제로 부족했다는 신호다(plan.md §후속의 TTL 재조정 근거).
	 * Redis 장애는 {@link RedisLock}이 이미 {@code WARN}으로 남겼으므로 여기서 다시 남기지 않는다.
	 */
	public void unlock(LocalDate tradingDate, String token) {
		if (redisLock.unlock(lockKey(tradingDate), token) == RedisLock.UnlockResult.NOT_HELD) {
			log.warn(
				"주식 수집 락 해제가 아무 것도 지우지 못했다(토큰 불일치 - TTL이 이미 만료돼 다른 "
					+ "인스턴스가 락을 새로 잡았을 수 있다) - tradingDate={}",
				tradingDate);
		}
	}

	private String lockKey(LocalDate tradingDate) {
		return KEY_PREFIX + tradingDate;
	}
}
