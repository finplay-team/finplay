package com.finplay.api.domain.ranking.service;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.ranking.config.RankingRebuildProperties;
import com.finplay.api.global.lock.RedisLock;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RankingRebuildLock {

	private static final String KEY_PREFIX = "ranking:rebuild:lock:";

	private final RedisLock redisLock;

	private final RankingRebuildProperties rebuildProperties;

	public Optional<String> tryLock(Market market) {
		return redisLock.tryLock(lockKey(market), Duration.ofSeconds(rebuildProperties.lockTtlSeconds()));
	}

	public void unlock(Market market, String token) {
		if (redisLock.unlock(lockKey(market), token) == RedisLock.UnlockResult.NOT_HELD) {
			log.warn(
				"랭킹 재구성 락 해제가 아무 것도 지우지 못했다(토큰 불일치 - TTL이 이미 만료돼 다른 "
					+ "인스턴스가 락을 새로 잡았을 수 있다) - market={}",
				market);
		}
	}

	private String lockKey(Market market) {
		return KEY_PREFIX + market;
	}
}
