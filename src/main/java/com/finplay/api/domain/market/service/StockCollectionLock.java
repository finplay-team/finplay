package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.config.MarketStockProperties;
import com.finplay.api.global.lock.RedisLock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockCollectionLock {

	private static final String KEY_PREFIX = "market:stock-collect:lock:";

	private final RedisLock redisLock;

	private final MarketStockProperties stockProperties;

	public Optional<String> tryLock(LocalDate tradingDate) {
		return redisLock.tryLock(
			lockKey(tradingDate), Duration.ofSeconds(stockProperties.collectLockTtlSeconds()));
	}

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
