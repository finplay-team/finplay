package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.global.lock.RedisLock;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoWatchLock {

	private static final String KEY_PREFIX = "feedback:crypto-watch:lock:";

	private final RedisLock redisLock;

	private final FeedbackCryptoProperties cryptoProperties;

	public Optional<String> tryLock(Long instrumentId) {
		return redisLock.tryLock(
			lockKey(instrumentId), Duration.ofSeconds(cryptoProperties.watchLockTtlSeconds()));
	}

	public void unlock(Long instrumentId, String token) {
		if (redisLock.unlock(lockKey(instrumentId), token) == RedisLock.UnlockResult.NOT_HELD) {
			log.warn(
				"코인 감시 락 해제가 아무 것도 지우지 못했다(토큰 불일치 - TTL이 이미 만료돼 다른 "
					+ "인스턴스가 락을 새로 잡았을 수 있다) - instrumentId={}",
				instrumentId);
		}
	}

	private String lockKey(Long instrumentId) {
		return KEY_PREFIX + instrumentId;
	}
}
